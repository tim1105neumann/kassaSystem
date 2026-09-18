import Fluent
import Foundation
import Testing
@testable import KassaServer

@Suite("Preisliste")
struct PriceListTests {
    @Test("Die echte CSV ergibt 44 Artikel")
    func parsesRealFile() throws {
        let rows = try PriceList.parse(String(contentsOf: priceListURL, encoding: .utf8))
        #expect(rows.count == 44)
        #expect(rows.first == PriceList.Row(category: "Speisen", name: "Berner Würstel mit Gebäck", priceCents: 620))
        #expect(rows.last == PriceList.Row(category: "Getraenke", name: "Leitungswasser", priceCents: 0))
    }

    /// Die echte Preisliste kommt seit der Aufteilung der Sammelposten ohne
    /// Komma im Artikelnamen aus — der Parser muss eines trotzdem können, sonst
    /// zerlegt die nächste Preisänderung einen Namen in zwei Felder.
    @Test("Kommas innerhalb eines Feldes bleiben im Namen")
    func keepsCommasInsideQuotedFields() throws {
        let rows = try PriceList.parse("""
            "Kategorie","Artikel","Preis"
            "Getraenke","Limo (Cola, Frucade, Almdudler, Eistee, Sprite)","3.10"
            """)
        #expect(rows.count == 1)
        #expect(rows[0].name == "Limo (Cola, Frucade, Almdudler, Eistee, Sprite)")
        #expect(rows[0].priceCents == 310)
    }

    @Test("Preise werden ohne Fließkomma exakt in Cent umgerechnet")
    func parsesPricesExactly() throws {
        let rows = try PriceList.parse("""
            "Kategorie","Artikel","Preis"
            "Speisen","Neun zwanzig","9.20"
            "Getraenke","Leitungswasser","0.00"
            "Getraenke","Sechs zwanzig","6.20"
            """)
        #expect(rows.map(\.priceCents) == [920, 0, 620])
    }

    @Test("Verdoppelte Anführungszeichen und CRLF")
    func handlesEscapedQuotesAndCRLF() throws {
        let rows = try PriceList.parse("\"Kategorie\",\"Artikel\",\"Preis\"\r\n\"Speisen\",\"Sagt \"\"Hallo\"\", ja\",\"1.50\"\r\n")
        #expect(rows.count == 1)
        #expect(rows[0].name == "Sagt \"Hallo\", ja")
        #expect(rows[0].priceCents == 150)
    }

    @Test("Import legt 44 Artikel an und zählt die Katalogversion hoch")
    func firstImport() async throws {
        try await withKassaApp { harness in
            let before = try await ServerState.current(on: harness.db).catalogVersion
            try await harness.importPriceList()

            let articles = try await Article.query(on: harness.db).all()
            #expect(articles.count == 44)
            #expect(articles.filter { !$0.active }.isEmpty)
            #expect(try await ServerState.current(on: harness.db).catalogVersion == before + 1)
        }
    }

    @Test("Zweiter Import mit geändertem Preis behält IDs und dupliziert nicht")
    func secondImportKeepsIDs() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let idsBefore = Set(try await Article.query(on: harness.db).all().map { try $0.requireID() })
            let versionBefore = try await ServerState.current(on: harness.db).catalogVersion

            // Nur der Preis ändert sich — die ID hängt an Kategorie und Name.
            let changed = try String(contentsOf: priceListURL, encoding: .utf8)
                .replacingOccurrences(of: "\"Berner Würstel mit Gebäck\",\"6.20\"", with: "\"Berner Würstel mit Gebäck\",\"7.10\"")
            try await PriceList.import(rows: try PriceList.parse(changed), on: harness.db)

            let after = try await Article.query(on: harness.db).all()
            #expect(after.count == 44)
            #expect(Set(try after.map { try $0.requireID() }) == idsBefore)
            #expect(try #require(after.first { $0.name == "Berner Würstel mit Gebäck" }).priceCents == 710)
            #expect(try await ServerState.current(on: harness.db).catalogVersion == versionBefore + 1)
        }
    }

    @Test("Ein entfernter Artikel wird inaktiv, nicht gelöscht")
    func removedArticleBecomesInactive() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let reduced = try String(contentsOf: priceListURL, encoding: .utf8)
                .replacingOccurrences(of: "\"Getraenke\",\"Leitungswasser\",\"0.00\"", with: "")
            try await PriceList.import(rows: try PriceList.parse(reduced), on: harness.db)

            let all = try await Article.query(on: harness.db).all()
            #expect(all.count == 44) // nichts gelöscht
            let water = try #require(all.first { $0.name == "Leitungswasser" })
            #expect(water.active == false)

            let token = try await harness.login().token
            let visible = try await harness.articles(token: token)
            #expect(visible.count == 43)
            #expect(!visible.contains { $0.name == "Leitungswasser" })
        }
    }

    @Test("Die Artikel-ID ist der gekürzte SHA256 aus Kategorie und Name")
    func deterministicID() {
        let id = PriceList.articleID(category: "Speisen", name: "Kaffee")
        #expect(id.count == 16)
        #expect(id == PriceList.articleID(category: "Speisen", name: "Kaffee"))
        #expect(id != PriceList.articleID(category: "Getraenke", name: "Kaffee"))
    }
}
