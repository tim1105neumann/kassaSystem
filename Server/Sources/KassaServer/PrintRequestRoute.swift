import Fluent
import Foundation
import KassaShared
import Vapor

@Sendable
func createPrintRequest(request: Request) async throws -> Response {
    let body = try request.content.decode(CreatePrintRequestRequest.self)
    let deviceId = try request.device.requireID()

    // Doppelte lineIds im Request summieren sich; `order` hält die Reihenfolge
    // fest, damit die Positionen auf dem Zettel so stehen wie am Bildschirm.
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
            // 1. Idempotenz — ein zweimal gedrückter Knopf darf keinen zweiten
            //    Zettel erzeugen.
            if let existing = try await PrintRequest.find(body.id, on: db) {
                return try existing.dto()
            }

            // 2. Zeilen laden und prüfen. Dieselben Gründe wie beim Kassieren:
            //    was gleich nicht kassiert werden könnte, darf auch nicht als
            //    Aufstellung auf den Tisch.
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

            // 3. Name, Preis und Summe kommen vom Server, nicht vom Client. Ein
            //    veralteter Client darf keine falsche Summe auf Papier bringen —
            //    der Gast rechnet danach nach.
            let items = order.map { lineID in
                let line = byID[lineID]!
                return PrintRequestDTO.Item(
                    name: line.nameSnapshot,
                    qty: wanted[lineID]!,
                    unitPriceCents: line.unitPriceCents
                )
            }
            let total = items.reduce(0) { $0 + $1.unitPriceCents * $1.qty }

            // Die Zeilen bleiben unangetastet: ein Druckauftrag ist reines Lesen
            // plus ein neuer Datensatz, nichts wird gesplittet oder zugeordnet.
            let printRequest = try PrintRequest(
                id: body.id,
                tableNumber: body.tableNumber,
                items: items,
                totalCents: total,
                requestedAt: body.requestedAt,
                deviceId: deviceId,
                updatedSeq: seq
            )
            try await printRequest.create(on: db)

            return try printRequest.dto()
        }
        return try await dto.encodeResponse(for: request)
    } catch let conflict as SettlementConflict {
        return try await conflict.response(for: request)
    }
}

/// Bewusst ohne die `since == 0`-Sonderbehandlung von `/sync`: der Druckdienst
/// hält keinen Gesamtstand, sondern verwirft alte Aufträge selbst über eine
/// Altersgrenze — ein Neustart darf nicht den ganzen Abend nachdrucken.
@Sendable
func printRequests(request: Request) async throws -> [PrintRequestDTO] {
    let since = request.query[Int.self, at: "since"] ?? 0
    return try await PrintRequest.query(on: request.db)
        .filter(\.$updatedSeq > since)
        .sort(\.$updatedSeq)
        .all()
        .map { try $0.dto() }
}
