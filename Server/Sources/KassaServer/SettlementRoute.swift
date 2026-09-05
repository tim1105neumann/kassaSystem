import Fluent
import Foundation
import KassaShared
import Vapor

/// Der Betriebstag hängt an `paidAt` — und das kommt vom Gerät. Ein iPhone mit
/// falsch gestellter Uhr würde damit in den falschen Tagesabschluss buchen, und
/// der stimmt dann nicht mehr gegen die Strichliste.
///
/// Die Client-Zeit wird trotzdem bevorzugt, weil eine Buchung aus der
/// Offline-Queue Stunden später ankommen kann und dann zu Recht noch zum
/// damaligen Betriebstag zählt. Sie gilt nur, solange sie plausibel ist:
/// innerhalb von `tolerance` um die Serverzeit. Eine Uhr, die um Stunden
/// falsch geht, richtet dabei keinen Schaden an (der Cutoff liegt um 06:00, da
/// kassiert niemand); eine, die um Tage falsch geht, würde den Umsatz aus dem
/// Tagesabschluss verschwinden lassen — und genau die wird abgefangen.
func plausiblePaidAt(_ claimed: Date, now: Date = Date(), tolerance: TimeInterval) -> Date {
    abs(claimed.timeIntervalSince(now)) <= tolerance ? claimed : now
}

@Sendable
func createSettlement(request: Request) async throws -> Response {
    let body = try request.content.decode(CreateSettlementRequest.self)
    let deviceId = try request.device.requireID()
    let cutoffHour = request.kassa.cutoffHour

    let paidAt = plausiblePaidAt(body.paidAt, tolerance: request.kassa.paidAtTolerance)
    if paidAt != body.paidAt {
        request.logger.warning(
            "Unplausibles paidAt von Gerät \(deviceId): \(body.paidAt) — Serverzeit verwendet. Uhr des Geräts prüfen."
        )
    }

    // Doppelte lineIds im Request summieren sich; `order` hält die Reihenfolge
    // fest, damit die Konfliktmeldung reproduzierbar ist.
    let (order, wanted): ([UUID], [UUID: Int]) = {
        var order: [UUID] = []
        var wanted: [UUID: Int] = [:]
        for selection in body.lines {
            if wanted[selection.lineId] == nil { order.append(selection.lineId) }
            wanted[selection.lineId, default: 0] += selection.qty
        }
        return (order, wanted)
    }()
    guard !order.isEmpty else {
        throw Abort(.badRequest, reason: "Keine Zeilen ausgewählt")
    }

    do {
        let dto = try await request.mutate { db, seq in
            // 1. Idempotenz — derselbe Kassiervorgang zweimal ist kein Fehler.
            if let existing = try await Settlement.find(body.id, on: db) {
                return try existing.dto()
            }

            // 2. Zeilen laden und prüfen.
            let loaded = try await OrderLine.query(on: db).filter(\.$id ~~ order).all()
            var byID: [UUID: OrderLine] = [:]
            for line in loaded { byID[try line.requireID()] = line }

            let unknown = order.filter { byID[$0] == nil }
            guard unknown.isEmpty else {
                throw SettlementConflict(conflictingLineIds: unknown, settledByDevice: nil, reason: .unknownLine)
            }

            let voided = order.filter { byID[$0]!.voidedAt != nil }
            guard voided.isEmpty else {
                throw SettlementConflict(conflictingLineIds: voided, settledByDevice: nil, reason: .lineVoided)
            }

            let settled = order.filter { byID[$0]!.settlementId != nil }
            if let first = settled.first {
                throw SettlementConflict(
                    conflictingLineIds: settled,
                    settledByDevice: try await settlingDeviceName(of: byID[first]!.settlementId!, on: db),
                    reason: .alreadySettled
                )
            }

            let exceeded = order.filter { wanted[$0]! < 1 || wanted[$0]! > byID[$0]!.qty }
            guard exceeded.isEmpty else {
                throw SettlementConflict(conflictingLineIds: exceeded, settledByDevice: nil, reason: .quantityExceeded)
            }

            // 3. Serverseitig nachrechnen — ein veralteter Client darf keine
            //    Summe diktieren.
            let total = order.reduce(0) { $0 + byID[$1]!.unitPriceCents * wanted[$1]! }
            guard total == body.amountCents else {
                throw SettlementConflict(conflictingLineIds: order, settledByDevice: nil, reason: .amountMismatch)
            }

            let settlement = Settlement(
                id: body.id,
                tableNumber: body.tableNumber,
                totalCents: total,
                paidAt: paidAt,
                deviceId: deviceId,
                businessDay: BusinessDay.day(for: paidAt, cutoffHour: cutoffHour),
                updatedSeq: seq
            )
            try await settlement.create(on: db)
            let settlementID = try settlement.requireID()

            // 4. Zuordnen, bei Teilmengen splitten.
            for lineID in order {
                let line = byID[lineID]!
                let qty = wanted[lineID]!
                if qty == line.qty {
                    line.settlementId = settlementID
                    line.updatedSeq = seq
                    try await line.save(on: db)
                } else {
                    // Der kassierte Anteil wird eine eigene Zeile, der Rest bleibt
                    // offen — so ist „kassiert" weiterhin ein binärer Zustand.
                    line.qty -= qty
                    line.updatedSeq = seq
                    try await line.save(on: db)

                    let settledPart = OrderLine(
                        id: UUID(),
                        tableNumber: line.tableNumber,
                        articleId: line.articleId,
                        nameSnapshot: line.nameSnapshot,
                        unitPriceCents: line.unitPriceCents,
                        qty: qty,
                        createdAt: line.createdAt,
                        deviceId: line.deviceId,
                        settlementId: settlementID,
                        updatedSeq: seq
                    )
                    try await settledPart.create(on: db)
                }
            }

            return try settlement.dto()
        }
        return try await dto.encodeResponse(for: request)
    } catch let conflict as SettlementConflict {
        return try await conflict.response(for: request)
    }
}
