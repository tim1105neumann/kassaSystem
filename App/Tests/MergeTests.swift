import Foundation
import KassaShared
import Testing
@testable import Kassa

@MainActor
@Suite("Delta-Merge")
struct MergeTests {

    private func line(id: UUID, qty: Int, seq: Int, table: Int = 4) -> OrderLineDTO {
        OrderLineDTO(
            id: id,
            tableNumber: table,
            articleId: "b1",
            nameSnapshot: "Bier, Radler 0,5 l",
            unitPriceCents: 440,
            qty: qty,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            deviceId: "anderes-geraet",
            updatedSeq: seq
        )
    }

    @Test("Serverzeile mit höherem updatedSeq überschreibt die lokale, maxSeq wächst mit")
    func higherSeqWins() throws {
        let env = try TestEnvironment()
        let id = UUID()

        env.store.merge(SyncResponse(lines: [line(id: id, qty: 1, seq: 5)], settlements: [], maxSeq: 5))
        #expect(env.settings.maxSeq == 5)
        #expect(env.openTotal(table: 4) == Money(cents: 440))

        env.store.merge(SyncResponse(lines: [line(id: id, qty: 3, seq: 9)], settlements: [], maxSeq: 9))
        #expect(env.settings.maxSeq == 9)
        #expect(env.store.lines(forTable: 4).count == 1)
        #expect(env.openTotal(table: 4) == Money(cents: 1_320))
    }

    @Test("Eine ältere Serverzeile überschreibt den neueren lokalen Stand nicht")
    func lowerSeqIsIgnored() throws {
        let env = try TestEnvironment()
        let id = UUID()

        env.store.merge(SyncResponse(lines: [line(id: id, qty: 3, seq: 9)], settlements: [], maxSeq: 9))
        env.store.merge(SyncResponse(lines: [line(id: id, qty: 1, seq: 4)], settlements: [], maxSeq: 9))

        #expect(env.openTotal(table: 4) == Money(cents: 1_320))
        #expect(env.settings.maxSeq == 9)
    }

    @Test("isFullReload ersetzt den Spiegel, behält aber ausstehende Commands und lokale Buchungen")
    func fullReloadKeepsPendingWork() throws {
        let env = try TestEnvironment()
        env.seedCatalog()

        let veraltet = UUID()
        env.store.merge(SyncResponse(lines: [line(id: veraltet, qty: 2, seq: 3)], settlements: [], maxSeq: 3))
        #expect(env.store.lines(forTable: 4).count == 1)

        // Offline gebucht, noch nicht bestätigt.
        let bier = try env.article("b1")
        env.store.addLines(tableNumber: 9, items: [(bier, 1)])
        #expect(env.store.openPendingCount == 1)

        let neu = UUID()
        env.store.merge(SyncResponse(
            lines: [line(id: neu, qty: 1, seq: 20, table: 6)],
            settlements: [],
            maxSeq: 20,
            isFullReload: true
        ))

        #expect(env.store.lines(forTable: 4).isEmpty, "alter Serverstand ist weg")
        #expect(env.openTotal(table: 6) == Money(cents: 440), "neuer Serverstand ist da")
        #expect(env.openTotal(table: 9) == Money(cents: 440), "lokale Buchung überlebt")
        #expect(env.store.openPendingCount == 1, "PendingCommand wurde nicht weggeworfen")
        #expect(env.settings.maxSeq == 20)
    }

    @Test("Stornierte und kassierte Zeilen zählen nicht zur offenen Tischsumme")
    func openTotalIgnoresVoidedAndSettled() throws {
        let env = try TestEnvironment()

        var voided = line(id: UUID(), qty: 2, seq: 1, table: 8)
        voided.voidedAt = Date(timeIntervalSince1970: 1_700_000_100)
        var settled = line(id: UUID(), qty: 5, seq: 2, table: 8)
        settled.settlementId = UUID()
        let open = line(id: UUID(), qty: 3, seq: 3, table: 8)

        env.store.merge(SyncResponse(lines: [voided, settled, open], settlements: [], maxSeq: 3))

        #expect(env.store.lines(forTable: 8).count == 3)
        #expect(env.openTotal(table: 8) == Money(cents: 1_320))
    }
}
