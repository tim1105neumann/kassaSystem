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

    @Test("Die Notiz wird gespeichert und kommt über /sync beim Druckdienst an")
    func noteSurvivesSync() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)

            let id = UUID()
            let response = try await harness.book(
                [NewOrderLine(id: id, tableNumber: 3, articleId: kaffee.id, qty: 1, createdAt: Date(), note: "ohne Senf")],
                token: token
            )
            #expect(try harness.decode([OrderLineDTO].self, from: response)[0].note == "ohne Senf")

            let delta = try await harness.sync(since: 0, token: token)
            #expect(delta.lines.first { $0.id == id }?.note == "ohne Senf")
        }
    }

    @Test("Eine Buchung ohne Notiz bleibt nil, Leerraum zählt als keine Notiz")
    func blankNoteBecomesNil() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)

            let ohne = try await harness.book(
                [NewOrderLine(id: UUID(), tableNumber: 3, articleId: kaffee.id, qty: 1, createdAt: Date())],
                token: token
            )
            #expect(try harness.decode([OrderLineDTO].self, from: ohne)[0].note == nil)

            let leer = try await harness.book(
                [NewOrderLine(id: UUID(), tableNumber: 3, articleId: kaffee.id, qty: 1, createdAt: Date(), note: "   \n ")],
                token: token
            )
            #expect(try harness.decode([OrderLineDTO].self, from: leer)[0].note == nil)
        }
    }

    @Test("Die Notiz wird getrimmt und bei Überlänge abgeschnitten statt abgelehnt")
    func noteIsTrimmedAndCapped() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)

            let getrimmt = try await harness.book(
                [NewOrderLine(id: UUID(), tableNumber: 3, articleId: kaffee.id, qty: 1, createdAt: Date(), note: "  extra   Ketchup\n")],
                token: token
            )
            #expect(try harness.decode([OrderLineDTO].self, from: getrimmt)[0].note == "extra Ketchup")

            let zuLang = String(repeating: "a", count: OrderNote.maxLength + 80)
            let response = try await harness.book(
                [NewOrderLine(id: UUID(), tableNumber: 3, articleId: kaffee.id, qty: 1, createdAt: Date(), note: zuLang)],
                token: token
            )
            #expect(response.status == .ok)
            #expect(try harness.decode([OrderLineDTO].self, from: response)[0].note
                == String(repeating: "a", count: OrderNote.maxLength))
        }
    }

    /// Ein `\n` in der Notiz käme als rohes 0x0A mitten in einer Bonzeile beim
    /// Drucker an und zerriss das Layout — also muss es hier schon weg sein.
    @Test("Zeilenumbrüche und Tabs in der Notiz werden zu Leerzeichen")
    func controlCharactersBecomeSpaces() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)

            for eingabe in ["ohne\nSenf", "ohne\tSenf", "ohne\r\nSenf"] {
                let response = try await harness.book(
                    [NewOrderLine(id: UUID(), tableNumber: 3, articleId: kaffee.id, qty: 1, createdAt: Date(), note: eingabe)],
                    token: token
                )
                #expect(try harness.decode([OrderLineDTO].self, from: response)[0].note == "ohne Senf")
            }
        }
    }

    @Test("Typographische Zeichen des iOS-Keyboards werden auf ASCII gebracht")
    func typographicCharactersAreNormalized() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)

            let response = try await harness.book(
                [NewOrderLine(
                    id: UUID(), tableNumber: 3, articleId: kaffee.id, qty: 1, createdAt: Date(),
                    note: "Kind\u{2019}s Portion \u{2013} „süß\u{201C} ohne \u{201C}scharf\u{201D}"
                )],
                token: token
            )
            // Umlaute bleiben: die beherrscht der Drucker, im Gegensatz zu ’ – “ ”.
            #expect(try harness.decode([OrderLineDTO].self, from: response)[0].note
                == "Kind's Portion - „süß\" ohne \"scharf\"")
        }
    }

    /// Idempotenz gilt auch für die Notiz: die Offline-Queue schickt denselben
    /// Command nach, während der Kellner die Notiz am Gerät schon geändert hat.
    @Test("Ein zweiter Versand mit anderer Notiz überschreibt die gespeicherte nicht")
    func replayDoesNotOverwriteNote() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)

            let id = UUID()
            let created = Date()
            try await harness.book(
                [NewOrderLine(id: id, tableNumber: 3, articleId: kaffee.id, qty: 1, createdAt: created, note: "ohne Senf")],
                token: token
            )
            let response = try await harness.book(
                [NewOrderLine(id: id, tableNumber: 3, articleId: kaffee.id, qty: 1, createdAt: created, note: "extra Ketchup")],
                token: token
            )
            #expect(try harness.decode([OrderLineDTO].self, from: response)[0].note == "ohne Senf")
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
