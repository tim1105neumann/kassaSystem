import Fluent
import Foundation
import KassaShared
import Testing
import VaporTesting
@testable import KassaServer

@Suite("Kassieren")
struct SettlementTests {
    /// Bucht `qty` Berner Würstel mit Gebäck (6,20 €) auf einen Tisch.
    private func bookBerner(_ harness: Harness, token: String, table: Int, qty: Int) async throws -> UUID {
        let article = try await harness.article(named: "Berner Würstel mit Gebäck", token: token)
        let id = UUID()
        try await harness.book(
            [NewOrderLine(id: id, tableNumber: table, articleId: article.id, qty: qty, createdAt: Date())],
            token: token
        )
        return id
    }

    @Test("Derselbe Kassiervorgang zweimal erzeugt genau ein Settlement")
    func idempotentSettlement() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let lineID = try await bookBerner(harness, token: token, table: 5, qty: 2)

            let request = CreateSettlementRequest(
                id: UUID(), tableNumber: 5,
                lines: [SettlementLineSelection(lineId: lineID, qty: 2)],
                amountCents: 1240, paidAt: Date()
            )
            let first = try await harness.send(.POST, APIRoute.settlements, token: token, body: request)
            let second = try await harness.send(.POST, APIRoute.settlements, token: token, body: request)

            #expect(first.status == .ok)
            #expect(second.status == .ok)
            #expect(try harness.decode(SettlementDTO.self, from: first) == harness.decode(SettlementDTO.self, from: second))
            #expect(try await Settlement.query(on: harness.db).count() == 1)
            #expect(try await OrderLine.query(on: harness.db).count() == 1)
        }
    }

    @Test("Ein zweiter Kassiervorgang auf dieselbe Zeile nennt das erste Gerät")
    func alreadySettledNamesDevice() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let schank = try await harness.login(deviceName: "Schank")
            let garten = try await harness.login(deviceName: "Gastgarten")
            let lineID = try await bookBerner(harness, token: schank.token, table: 7, qty: 1)

            let first = try await harness.send(.POST, APIRoute.settlements, token: schank.token, body: CreateSettlementRequest(
                id: UUID(), tableNumber: 7,
                lines: [SettlementLineSelection(lineId: lineID, qty: 1)],
                amountCents: 620, paidAt: Date()
            ))
            #expect(first.status == .ok)

            let second = try await harness.send(.POST, APIRoute.settlements, token: garten.token, body: CreateSettlementRequest(
                id: UUID(), tableNumber: 7,
                lines: [SettlementLineSelection(lineId: lineID, qty: 1)],
                amountCents: 620, paidAt: Date()
            ))
            #expect(second.status == .conflict)
            let conflict = try harness.decode(SettlementConflictDTO.self, from: second)
            #expect(conflict.reason == .alreadySettled)
            #expect(conflict.conflictingLineIds == [lineID])
            #expect(conflict.settledByDevice == "Schank")
            #expect(try await Settlement.query(on: harness.db).count() == 1)
        }
    }

    @Test("Teilmenge: 2 von 3 kassiert, der Rest bleibt am Tisch offen")
    func partialSettlementSplitsLine() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let lineID = try await bookBerner(harness, token: token, table: 8, qty: 3)

            let response = try await harness.send(.POST, APIRoute.settlements, token: token, body: CreateSettlementRequest(
                id: UUID(), tableNumber: 8,
                lines: [SettlementLineSelection(lineId: lineID, qty: 2)],
                amountCents: 1240, paidAt: Date()
            ))
            #expect(response.status == .ok)
            #expect(try harness.decode(SettlementDTO.self, from: response).totalCents == 1240)

            let lines = try await OrderLine.query(on: harness.db).all()
            #expect(lines.count == 2)

            let rest = try #require(lines.first { $0.id == lineID })
            #expect(rest.qty == 1)
            #expect(rest.settlementId == nil)

            let settled = try #require(lines.first { $0.id != lineID })
            #expect(settled.qty == 2)
            #expect(settled.settlementId != nil)
            #expect(settled.unitPriceCents == 620)
            #expect(settled.nameSnapshot == "Berner Würstel mit Gebäck")

            // Die Gesamtsumme der Zeilen darf sich durch den Split nicht ändern.
            #expect(lines.reduce(0) { $0 + $1.unitPriceCents * $1.qty } == 1860)
            #expect(lines.filter { $0.settlementId == nil }.reduce(0) { $0 + $1.unitPriceCents * $1.qty } == 620)
        }
    }

    @Test("Trinkgeld wird gespeichert, ohne den Umsatz zu verändern")
    func settlementWithTip() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let lineID = try await bookBerner(harness, token: token, table: 6, qty: 2)

            let response = try await harness.send(.POST, APIRoute.settlements, token: token, body: CreateSettlementRequest(
                id: UUID(), tableNumber: 6,
                lines: [SettlementLineSelection(lineId: lineID, qty: 2)],
                amountCents: 1240, paidAt: Date(),
                tipCents: 130
            ))
            #expect(response.status == .ok)
            let dto = try harness.decode(SettlementDTO.self, from: response)
            #expect(dto.totalCents == 1240)
            #expect(dto.tipCents == 130)
            #expect(dto.grandTotal.cents == 1370)

            let stored = try #require(try await Settlement.find(dto.id, on: harness.db))
            #expect(stored.totalCents == 1240)
            #expect(stored.tipCents == 130)
        }
    }

    @Test("Ein negatives Trinkgeld wird abgelehnt")
    func negativeTipRejected() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let lineID = try await bookBerner(harness, token: token, table: 6, qty: 1)

            let response = try await harness.send(.POST, APIRoute.settlements, token: token, body: CreateSettlementRequest(
                id: UUID(), tableNumber: 6,
                lines: [SettlementLineSelection(lineId: lineID, qty: 1)],
                amountCents: 620, paidAt: Date(),
                tipCents: -100
            ))
            #expect(response.status == .badRequest)
            #expect(try await Settlement.query(on: harness.db).count() == 0)

            let line = try #require(try await OrderLine.find(lineID, on: harness.db))
            #expect(line.settlementId == nil)
        }
    }

    @Test("Ein falscher Betrag wird abgelehnt und lässt die Zeilen unangetastet")
    func amountMismatch() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let lineID = try await bookBerner(harness, token: token, table: 9, qty: 2)

            let response = try await harness.send(.POST, APIRoute.settlements, token: token, body: CreateSettlementRequest(
                id: UUID(), tableNumber: 9,
                lines: [SettlementLineSelection(lineId: lineID, qty: 2)],
                amountCents: 620, // veralteter Client-Stand
                paidAt: Date()
            ))
            #expect(response.status == .conflict)
            #expect(try harness.decode(SettlementConflictDTO.self, from: response).reason == .amountMismatch)

            #expect(try await Settlement.query(on: harness.db).count() == 0)
            let line = try #require(try await OrderLine.find(lineID, on: harness.db))
            #expect(line.qty == 2)
            #expect(line.settlementId == nil)
            #expect(line.voidedAt == nil)
        }
    }

    @Test("Unbekannte, stornierte und zu große Mengen geben je den eigenen Grund")
    func otherConflictReasons() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token

            // Unbekannte Zeile
            let unknownID = UUID()
            let unknown = try await harness.send(.POST, APIRoute.settlements, token: token, body: CreateSettlementRequest(
                id: UUID(), tableNumber: 1,
                lines: [SettlementLineSelection(lineId: unknownID, qty: 1)],
                amountCents: 620, paidAt: Date()
            ))
            #expect(unknown.status == .conflict)
            #expect(try harness.decode(SettlementConflictDTO.self, from: unknown).reason == .unknownLine)

            // Stornierte Zeile
            let voidedID = try await bookBerner(harness, token: token, table: 1, qty: 1)
            #expect(try await harness.send(.POST, APIRoute.voidLine(voidedID), token: token).status == .ok)
            let voided = try await harness.send(.POST, APIRoute.settlements, token: token, body: CreateSettlementRequest(
                id: UUID(), tableNumber: 1,
                lines: [SettlementLineSelection(lineId: voidedID, qty: 1)],
                amountCents: 620, paidAt: Date()
            ))
            #expect(voided.status == .conflict)
            #expect(try harness.decode(SettlementConflictDTO.self, from: voided).reason == .lineVoided)

            // Mehr kassieren als gebucht
            let smallID = try await bookBerner(harness, token: token, table: 2, qty: 1)
            let exceeded = try await harness.send(.POST, APIRoute.settlements, token: token, body: CreateSettlementRequest(
                id: UUID(), tableNumber: 2,
                lines: [SettlementLineSelection(lineId: smallID, qty: 5)],
                amountCents: 3100, paidAt: Date()
            ))
            #expect(exceeded.status == .conflict)
            let dto = try harness.decode(SettlementConflictDTO.self, from: exceeded)
            #expect(dto.reason == .quantityExceeded)
            #expect(dto.conflictingLineIds == [smallID])

            #expect(try await Settlement.query(on: harness.db).count() == 0)
        }
    }
}
