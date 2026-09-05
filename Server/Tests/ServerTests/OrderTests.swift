import Fluent
import Foundation
import KassaShared
import Testing
import VaporTesting
@testable import KassaServer

@Suite("Bestellzeilen")
struct OrderTests {
    @Test("Derselbe Buchungs-Command zweimal erzeugt genau eine Zeile")
    func idempotentBooking() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)

            let line = NewOrderLine(id: UUID(), tableNumber: 3, articleId: kaffee.id, qty: 2, createdAt: Date())
            let first = try harness.decode([OrderLineDTO].self, from: try await harness.book([line], token: token))
            let second = try harness.decode([OrderLineDTO].self, from: try await harness.book([line], token: token))

            #expect(first == second)
            #expect(try await OrderLine.query(on: harness.db).count() == 1)
        }
    }

    @Test("Eine erneut gesendete Zeile wird nicht überschrieben")
    func replayDoesNotOverwrite() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)

            let id = UUID()
            try await harness.book([NewOrderLine(id: id, tableNumber: 3, articleId: kaffee.id, qty: 2, createdAt: Date())], token: token)
            let response = try await harness.book(
                [NewOrderLine(id: id, tableNumber: 9, articleId: kaffee.id, qty: 99, createdAt: Date())],
                token: token
            )
            let lines = try harness.decode([OrderLineDTO].self, from: response)
            #expect(lines[0].tableNumber == 3)
            #expect(lines[0].qty == 2)
        }
    }

    @Test("Name und Preis kommen vom Server, nicht vom Client")
    func serverFillsSnapshot() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let article = try await harness.article(named: "Berner Würstel mit Pommes", token: token)

            let response = try await harness.book(
                [NewOrderLine(id: UUID(), tableNumber: 1, articleId: article.id, qty: 3, createdAt: Date())],
                token: token
            )
            let line = try harness.decode([OrderLineDTO].self, from: response)[0]
            #expect(line.nameSnapshot == "Berner Würstel mit Pommes")
            #expect(line.unitPriceCents == 920)
            #expect(line.lineTotal.cents == 2760)
        }
    }

    @Test("Ungültige Buchungen werden abgelehnt")
    func rejectsInvalidBookings() async throws {
        try await withKassaApp(tableCount: 40) { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)

            // Fremde Artikel-ID
            try await harness.book(
                [NewOrderLine(id: UUID(), tableNumber: 1, articleId: "gibtsnicht", qty: 1, createdAt: Date())],
                token: token, expect: .badRequest
            )
            // Menge 0
            try await harness.book(
                [NewOrderLine(id: UUID(), tableNumber: 1, articleId: kaffee.id, qty: 0, createdAt: Date())],
                token: token, expect: .badRequest
            )
            // Tisch außerhalb 1...40
            try await harness.book(
                [NewOrderLine(id: UUID(), tableNumber: 41, articleId: kaffee.id, qty: 1, createdAt: Date())],
                token: token, expect: .badRequest
            )
            #expect(try await OrderLine.query(on: harness.db).count() == 0)
        }
    }

    @Test("Ein inaktiver Artikel lässt sich nicht mehr buchen")
    func rejectsInactiveArticle() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let water = try await harness.article(named: "Leitungswasser", token: token)

            let reduced = try String(contentsOf: priceListURL, encoding: .utf8)
                .replacingOccurrences(of: "\"Getraenke\",\"Leitungswasser\",\"0.00\"", with: "")
            try await PriceList.import(rows: try PriceList.parse(reduced), on: harness.db)

            try await harness.book(
                [NewOrderLine(id: UUID(), tableNumber: 1, articleId: water.id, qty: 1, createdAt: Date())],
                token: token, expect: .badRequest
            )
        }
    }

    @Test("Stornieren ist idempotent, nach dem Kassieren aber gesperrt")
    func voiding() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)

            let openID = UUID()
            let settledID = UUID()
            try await harness.book([
                NewOrderLine(id: openID, tableNumber: 4, articleId: kaffee.id, qty: 1, createdAt: Date()),
                NewOrderLine(id: settledID, tableNumber: 4, articleId: kaffee.id, qty: 1, createdAt: Date()),
            ], token: token)

            // Zweimal stornieren: beides 200, storniert bleibt storniert.
            let first = try await harness.send(.POST, APIRoute.voidLine(openID), token: token)
            #expect(first.status == .ok)
            let voidedAt = try harness.decode(OrderLineDTO.self, from: first).voidedAt
            let second = try await harness.send(.POST, APIRoute.voidLine(openID), token: token)
            #expect(second.status == .ok)
            #expect(try harness.decode(OrderLineDTO.self, from: second).voidedAt == voidedAt)

            // Kassieren, dann stornieren -> 409
            let settle = CreateSettlementRequest(
                id: UUID(), tableNumber: 4,
                lines: [SettlementLineSelection(lineId: settledID, qty: 1)],
                amountCents: 300, paidAt: Date()
            )
            #expect(try await harness.send(.POST, APIRoute.settlements, token: token, body: settle).status == .ok)

            let conflict = try await harness.send(.POST, APIRoute.voidLine(settledID), token: token)
            #expect(conflict.status == .conflict)
            let dto = try harness.decode(SettlementConflictDTO.self, from: conflict)
            #expect(dto.reason == .alreadySettled)
            #expect(dto.conflictingLineIds == [settledID])
        }
    }

    @Test("Eine unbekannte Zeile zu stornieren gibt 404")
    func voidUnknownLine() async throws {
        try await withKassaApp { harness in
            let token = try await harness.login().token
            let response = try await harness.send(.POST, APIRoute.voidLine(UUID()), token: token)
            #expect(response.status == .notFound)
        }
    }
}
