import Fluent
import Foundation
import KassaShared
import Testing
import VaporTesting
@testable import KassaServer

@Suite("Sync")
struct SyncTests {
    @Test("since=0 lädt alles und meldet einen vollen Nachladevorgang")
    func fullReload() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)
            try await harness.book(
                [NewOrderLine(id: UUID(), tableNumber: 2, articleId: kaffee.id, qty: 1, createdAt: Date())],
                token: token
            )

            let response = try await harness.sync(since: 0, token: token)
            #expect(response.isFullReload)
            #expect(response.lines.count == 1)
            #expect(response.maxSeq > 0)
        }
    }

    @Test("Ein Delta liefert genau die geänderten Zeilen")
    func deltaOnlyReturnsChanges() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)

            let alt = UUID()
            try await harness.book(
                [NewOrderLine(id: alt, tableNumber: 2, articleId: kaffee.id, qty: 1, createdAt: Date())],
                token: token
            )
            let afterFirst = try await harness.sync(since: 0, token: token)

            let neu = UUID()
            try await harness.book(
                [NewOrderLine(id: neu, tableNumber: 3, articleId: kaffee.id, qty: 2, createdAt: Date())],
                token: token
            )

            let delta = try await harness.sync(since: afterFirst.maxSeq, token: token)
            #expect(delta.isFullReload == false)
            #expect(delta.lines.map(\.id) == [neu])
            #expect(delta.settlements.isEmpty)
            #expect(delta.maxSeq > afterFirst.maxSeq)

            // Nichts Neues seither.
            let empty = try await harness.sync(since: delta.maxSeq, token: token)
            #expect(empty.lines.isEmpty)
            #expect(empty.maxSeq == delta.maxSeq)
        }
    }

    @Test("Stornieren und Kassieren tauchen im Delta auf")
    func mutationsShowUpInDelta() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)

            let lineID = UUID()
            try await harness.book(
                [NewOrderLine(id: lineID, tableNumber: 6, articleId: kaffee.id, qty: 1, createdAt: Date())],
                token: token
            )
            let base = try await harness.sync(since: 0, token: token)

            let settlementID = UUID()
            #expect(try await harness.send(.POST, APIRoute.settlements, token: token, body: CreateSettlementRequest(
                id: settlementID, tableNumber: 6,
                lines: [SettlementLineSelection(lineId: lineID, qty: 1)],
                amountCents: 300, paidAt: Date()
            )).status == .ok)

            let delta = try await harness.sync(since: base.maxSeq, token: token)
            #expect(delta.lines.map(\.id) == [lineID])
            #expect(delta.lines[0].settlementId == settlementID)
            #expect(delta.settlements.map(\.id) == [settlementID])
        }
    }

    @Test("Ein voller Nachladevorgang bringt alte offene Zeilen mit, alte kassierte nicht")
    func retentionKeepsOpenLines() async throws {
        // Toleranz aufgeweitet, weil dieser Test bewusst 40 Tage alte Daten
        // anlegt — im Betrieb würde `plausiblePaidAt` die auf jetzt ziehen.
        try await withKassaApp(paidAtTolerance: 365 * 24 * 3600) { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)
            let longAgo = Date().addingTimeInterval(-40 * 24 * 3600)

            let alteOffene = UUID()
            let alteKassierte = UUID()
            try await harness.book([
                NewOrderLine(id: alteOffene, tableNumber: 1, articleId: kaffee.id, qty: 1, createdAt: longAgo),
                NewOrderLine(id: alteKassierte, tableNumber: 1, articleId: kaffee.id, qty: 1, createdAt: longAgo),
            ], token: token)

            #expect(try await harness.send(.POST, APIRoute.settlements, token: token, body: CreateSettlementRequest(
                id: UUID(), tableNumber: 1,
                lines: [SettlementLineSelection(lineId: alteKassierte, qty: 1)],
                amountCents: 300, paidAt: longAgo
            )).status == .ok)

            let response = try await harness.sync(since: 0, token: token)
            #expect(response.lines.map(\.id) == [alteOffene])
            #expect(response.settlements.isEmpty)
        }
    }
}

@Suite("Sequenzzähler")
struct SequenceTests {
    @Test("Gleichzeitige Buchungen bekommen jede eine eigene Sequenznummer")
    func concurrentMutationsGetDistinctSequences() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)
            let ids = (0..<20).map { _ in UUID() }

            await withTaskGroup(of: Void.self) { group in
                for (index, id) in ids.enumerated() {
                    group.addTask {
                        _ = try? await harness.send(
                            .POST, APIRoute.orderLines, token: token,
                            body: CreateOrderLinesRequest(lines: [NewOrderLine(
                                id: id,
                                tableNumber: index % 40 + 1,
                                articleId: kaffee.id,
                                qty: 1,
                                createdAt: Date()
                            )])
                        )
                    }
                }
            }

            let lines = try await OrderLine.query(on: harness.db).all()
            #expect(lines.count == 20)
            #expect(Set(lines.map(\.updatedSeq)).count == 20)
            #expect(try await ServerState.current(on: harness.db).seq == 20)
        }
    }
}
