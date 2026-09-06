import Fluent
import Foundation
import KassaShared
import Testing
import VaporTesting
@testable import KassaServer

@Suite("Tagesabschluss")
struct ReportTests {
    private func settle(
        _ harness: Harness,
        token: String,
        table: Int,
        article: ArticleDTO,
        qty: Int,
        paidAt: Date,
        tip: Int = 0
    ) async throws {
        let lineID = UUID()
        try await harness.book(
            [NewOrderLine(id: lineID, tableNumber: table, articleId: article.id, qty: qty, createdAt: paidAt)],
            token: token
        )
        let response = try await harness.send(.POST, APIRoute.settlements, token: token, body: CreateSettlementRequest(
            id: UUID(), tableNumber: table,
            lines: [SettlementLineSelection(lineId: lineID, qty: qty)],
            amountCents: article.priceCents * qty,
            paidAt: paidAt,
            tipCents: tip
        ))
        #expect(response.status == .ok)
    }

    @Test("23:00 und 01:00 der Folgenacht sind derselbe Betriebstag, 07:00 nicht mehr")
    func businessDayCrossesMidnight() async throws {
        try await withKassaApp(cutoffHour: 6) { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let berner = try await harness.article(named: "Berner Würstel mit Gebäck", token: token)

            try await settle(harness, token: token, table: 1, article: berner, qty: 1, paidAt: viennaDate(2026, 9, 5, 23, 0))
            try await settle(harness, token: token, table: 2, article: berner, qty: 2, paidAt: viennaDate(2026, 9, 6, 1, 0))
            try await settle(harness, token: token, table: 3, article: berner, qty: 1, paidAt: viennaDate(2026, 9, 6, 7, 0))

            let abend = try harness.decode(
                DayReportDTO.self,
                from: try await harness.send(.GET, "\(APIRoute.dayReport)?date=2026-09-05", token: token)
            )
            #expect(abend.businessDay == "2026-09-05")
            #expect(abend.settlementCount == 2)
            #expect(abend.totalCents == 620 + 1240)

            let naechsterTag = try harness.decode(
                DayReportDTO.self,
                from: try await harness.send(.GET, "\(APIRoute.dayReport)?date=2026-09-06", token: token)
            )
            #expect(naechsterTag.settlementCount == 1)
            #expect(naechsterTag.totalCents == 620)
        }
    }

    @Test("Der Report summiert je Kategorie und nennt die Renner")
    func aggregates() async throws {
        try await withKassaApp(cutoffHour: 6) { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let berner = try await harness.article(named: "Berner Würstel mit Gebäck", token: token)      // Speisen, 620
            let bier = try await harness.article(named: "Bier 0,3 l", token: token)                        // Getraenke, 380
            let abend = viennaDate(2026, 9, 5, 20, 0)

            try await settle(harness, token: token, table: 1, article: berner, qty: 2, paidAt: abend)
            try await settle(harness, token: token, table: 2, article: bier, qty: 5, paidAt: abend)

            let report = try harness.decode(
                DayReportDTO.self,
                from: try await harness.send(.GET, "\(APIRoute.dayReport)?date=2026-09-05", token: token)
            )
            #expect(report.totalCents == 2 * 620 + 5 * 380)
            #expect(report.settlementCount == 2)
            #expect(report.byCategory == [
                DayReportDTO.CategoryTotal(category: "Getraenke", totalCents: 1900),
                DayReportDTO.CategoryTotal(category: "Speisen", totalCents: 1240),
            ])
            #expect(report.topArticles.map(\.name) == ["Bier 0,3 l", "Berner Würstel mit Gebäck"])
            #expect(report.topArticles.map(\.qty) == [5, 2])
            #expect(report.settlements.count == 2)
        }
    }

    @Test("Trinkgeld wird eigens ausgewiesen, der Umsatz bleibt die Warensumme")
    func tipsAreReportedSeparately() async throws {
        try await withKassaApp(cutoffHour: 6) { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let berner = try await harness.article(named: "Berner Würstel mit Gebäck", token: token)      // Speisen, 620
            let bier = try await harness.article(named: "Bier 0,3 l", token: token)                        // Getraenke, 380
            // Bewusst „jetzt": ein fixes Datum würde irgendwann aus der
            // paidAt-Toleranz fallen und der Umsatz landete im falschen Tag.
            let jetzt = Date()

            try await settle(harness, token: token, table: 1, article: berner, qty: 2, paidAt: jetzt, tip: 130)
            try await settle(harness, token: token, table: 2, article: bier, qty: 5, paidAt: jetzt, tip: 100)

            let tag = BusinessDay.day(for: jetzt, cutoffHour: 6)
            let report = try harness.decode(
                DayReportDTO.self,
                from: try await harness.send(.GET, "\(APIRoute.dayReport)?date=\(tag)", token: token)
            )
            #expect(report.totalCents == 2 * 620 + 5 * 380)
            #expect(report.tipCents == 230)
            #expect(report.grandTotal.cents == 3370)

            // Die Kategorien müssen weiterhin exakt auf den Umsatz aufgehen —
            // sonst wäre das Trinkgeld irgendwo in die Ware gerutscht.
            #expect(report.byCategory.reduce(0) { $0 + $1.totalCents } == report.totalCents)
            #expect(report.settlements.map(\.tipCents).reduce(0, +) == 230)
        }
    }

    @Test("Ohne Datum kommt der laufende Betriebstag, ein leerer Tag ist leer")
    func defaultsToCurrentDayAndHandlesEmpty() async throws {
        try await withKassaApp { harness in
            let token = try await harness.login().token
            let heute = try harness.decode(
                DayReportDTO.self,
                from: try await harness.send(.GET, APIRoute.dayReport, token: token)
            )
            #expect(heute.businessDay == BusinessDay.day(for: Date(), cutoffHour: 6))
            #expect(heute.totalCents == 0)
            #expect(heute.settlementCount == 0)
            #expect(heute.settlements.isEmpty)
            #expect(heute.topArticles.isEmpty)
        }
    }
}
