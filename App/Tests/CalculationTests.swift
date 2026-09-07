import Foundation
import KassaShared
import Testing
@testable import Kassa

@MainActor
@Suite("Rückgeld und Teilzahlung")
struct CalculationTests {

    @Test("20,00 € auf 13,60 € ergibt 6,40 € Rückgeld")
    func changeIsCorrect() {
        let result = ChangeCalculator.change(total: Money(cents: 1_360), given: Money(cents: 2_000))
        #expect(result == .success(Money(cents: 640)))
    }

    @Test("Passend gezahlt ergibt kein Rückgeld")
    func exactPayment() {
        let result = ChangeCalculator.change(total: Money(cents: 1_360), given: Money(cents: 1_360))
        #expect(result == .success(.zero))
    }

    @Test("Zu wenig Geld ist ein Fehler, kein negatives Rückgeld")
    func tooLittleIsAnError() {
        let result = ChangeCalculator.change(total: Money(cents: 1_360), given: Money(cents: 1_000))
        #expect(result == .failure(.notEnough(missing: Money(cents: 360))))

        if case .success(let value) = result {
            Issue.record("Es dürfte kein Rückgeld geben, geliefert wurde \(value.formatted)")
        }
    }

    @Test("Schnellwahl enthält den passenden Betrag und deckende Scheine")
    func quickAmounts() {
        let amounts = ChangeCalculator.quickAmounts(for: Money(cents: 1_360))
        #expect(amounts.contains(Money(cents: 1_360)))
        #expect(amounts.contains(Money(cents: 2_000)))
        #expect(amounts.allSatisfy { $0.cents >= 1_360 })
        #expect(amounts == amounts.sorted())
    }

    @Test("31,00 € auf 29,70 € ergibt 1,30 € Trinkgeld")
    func tipIsCorrect() {
        let result = TipCalculator.tip(total: Money(cents: 2_970), paid: Money(cents: 3_100))
        #expect(result == .success(Money(cents: 130)))
    }

    @Test("Genau die Rechnung ergibt kein Trinkgeld")
    func exactAmountHasNoTip() {
        let result = TipCalculator.tip(total: Money(cents: 2_970), paid: Money(cents: 2_970))
        #expect(result == .success(.zero))
    }

    @Test("Weniger als die Rechnung ist ein Fehler, kein negatives Trinkgeld")
    func belowTotalIsAnError() {
        let result = TipCalculator.tip(total: Money(cents: 2_970), paid: Money(cents: 2_900))
        #expect(result == .failure(.belowTotal(missing: Money(cents: 70))))

        if case .success(let value) = result {
            Issue.record("Es dürfte kein Trinkgeld geben, geliefert wurde \(value.formatted)")
        }
    }

    @Test("Aufrundungsvorschläge für 29,70 €")
    func quickTotals() {
        let totals = TipCalculator.quickTotals(for: Money(cents: 2_970))
        #expect(totals == [2_970, 3_000, 3_100, 3_200, 3_500].map(Money.init(cents:)))
    }

    @Test("Ohne Betrag gibt es nichts aufzurunden")
    func quickTotalsForZero() {
        #expect(TipCalculator.quickTotals(for: .zero).isEmpty)
    }

    @Test("Zwischensumme der ausgewählten Mengen stimmt")
    func partialSelectionSubtotal() throws {
        let env = try TestEnvironment()
        let krainerId = UUID()
        let bierId = UUID()

        env.store.merge(SyncResponse(
            lines: [
                OrderLineDTO(id: krainerId, tableNumber: 12, articleId: "a1", nameSnapshot: "Käsekrainer",
                             unitPriceCents: 620, qty: 2, createdAt: .now, deviceId: "d", updatedSeq: 1),
                OrderLineDTO(id: bierId, tableNumber: 12, articleId: "b1", nameSnapshot: "Bier",
                             unitPriceCents: 440, qty: 3, createdAt: .now, deviceId: "d", updatedSeq: 2)
            ],
            settlements: [],
            maxSeq: 2
        ))
        let lines = env.store.lines(forTable: 12)

        var selection = SettlementSelection()
        selection.set(1, for: krainerId, max: 2)
        selection.set(2, for: bierId, max: 3)

        #expect(selection.subtotal(over: lines) == Money(cents: 1_500))
        #expect(selection.requestLines(over: lines).count == 2)

        // Über die vorhandene Menge hinaus lässt sich nichts auswählen.
        selection.set(9, for: bierId, max: 3)
        #expect(selection.qty(for: bierId) == 3)
        #expect(selection.subtotal(over: lines) == Money(cents: 1_940))

        #expect(SettlementSelection.all(of: lines).subtotal(over: lines) == Money(cents: 2_560))
    }

    @Test("Aufstellung enthält genau die Teilbetrags-Auswahl")
    func printRequestCarriesPartialSelection() async throws {
        let env = try TestEnvironment()
        let krainerId = UUID()
        let bierId = UUID()

        env.store.merge(SyncResponse(
            lines: [
                OrderLineDTO(id: krainerId, tableNumber: 12, articleId: "a1", nameSnapshot: "Käsekrainer",
                             unitPriceCents: 620, qty: 2, createdAt: .now, deviceId: "d", updatedSeq: 1),
                OrderLineDTO(id: bierId, tableNumber: 12, articleId: "b1", nameSnapshot: "Bier",
                             unitPriceCents: 440, qty: 3, createdAt: .now, deviceId: "d", updatedSeq: 2)
            ],
            settlements: [],
            maxSeq: 2
        ))
        let lines = env.store.lines(forTable: 12)

        var selection = SettlementSelection()
        selection.set(1, for: krainerId, max: 2)

        let request = CreatePrintRequestRequest(
            id: UUID(),
            tableNumber: 12,
            lines: selection.requestLines(over: lines),
            requestedAt: .now
        )
        try await env.api.createPrintRequest(request)

        #expect(await env.api.serverPrintRequest(id: request.id)?.lines
            == [SettlementLineSelection(lineId: krainerId, qty: 1)])

        // Ein zweiter Druck braucht eine neue ID — dieselbe ergibt nur einen Zettel.
        try await env.api.createPrintRequest(request)
        #expect(await env.api.printRequestCallCount == 2)
        #expect(await env.api.serverPrintRequestCount == 1)
    }

    @Test("Teilzahlung reduziert die offene Tischsumme um den kassierten Anteil")
    func partialSettlementReducesOpenTotal() throws {
        let env = try TestEnvironment()
        let bierId = UUID()
        env.store.merge(SyncResponse(
            lines: [
                OrderLineDTO(id: bierId, tableNumber: 14, articleId: "b1", nameSnapshot: "Bier",
                             unitPriceCents: 440, qty: 3, createdAt: .now, deviceId: "d", updatedSeq: 1)
            ],
            settlements: [],
            maxSeq: 1
        ))

        env.store.settle(
            tableNumber: 14,
            selections: [SettlementLineSelection(lineId: bierId, qty: 2)],
            total: Money(cents: 880)
        )

        #expect(env.openTotal(table: 14) == Money(cents: 440))
        #expect(env.store.openPendingCount == 1)
    }

    @Test("Vollständiges Kassieren macht den Tisch frei")
    func fullSettlementClearsTable() throws {
        let env = try TestEnvironment()
        let bierId = UUID()
        env.store.merge(SyncResponse(
            lines: [
                OrderLineDTO(id: bierId, tableNumber: 15, articleId: "b1", nameSnapshot: "Bier",
                             unitPriceCents: 440, qty: 2, createdAt: .now, deviceId: "d", updatedSeq: 1)
            ],
            settlements: [],
            maxSeq: 1
        ))

        env.store.settle(
            tableNumber: 15,
            selections: [SettlementLineSelection(lineId: bierId, qty: 2)],
            total: Money(cents: 880)
        )

        #expect(env.openTotal(table: 15) == .zero)
    }

    @Test("Menge verringern storniert die Zeile und bucht den Rest neu")
    func decreasingQtyVoidsAndRebooks() throws {
        let env = try TestEnvironment()
        env.seedCatalog()
        let bier = try env.article("b1")

        env.store.addLines(tableNumber: 20, items: [(bier, 3)])
        let line = try #require(env.store.lines(forTable: 20).first)
        env.store.changeQty(of: line, to: 1)

        #expect(env.openTotal(table: 20) == Money(cents: 440))
        // Buchung + Storno + Restbuchung
        #expect(env.store.openPendingCount == 3)
    }
}
