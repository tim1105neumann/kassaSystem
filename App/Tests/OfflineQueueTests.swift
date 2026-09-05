import Foundation
import KassaShared
import Testing
@testable import Kassa

@MainActor
@Suite("Offline-Queue und Sync")
struct OfflineQueueTests {

    @Test("Offline gebuchte Zeilen erscheinen sofort in der Tischsumme")
    func offlineBookingIsVisibleImmediately() async throws {
        let env = try TestEnvironment()
        await env.api.setOffline(true)
        env.seedCatalog()

        let bier = try env.article("b1")
        env.store.addLines(tableNumber: 5, items: [(bier, 2)])

        #expect(env.openTotal(table: 5) == Money(cents: 880))
        #expect(env.store.openPendingCount == 1)
        #expect(await env.api.serverLineCount == 0)
    }

    @Test("Netz kommt zurück: Queue wird abgearbeitet, ohne Doppelbuchung")
    func queueDrainCausesNoDoubleBooking() async throws {
        let env = try TestEnvironment()
        env.seedCatalog()

        let bier = try env.article("b1")
        env.store.addLines(tableNumber: 5, items: [(bier, 2)])

        // Der Server wendet den Command an, die Antwort geht aber verloren.
        await env.api.setLoseNextResponse(true)
        await env.engine.syncNow()

        #expect(env.store.openPendingCount == 1, "Command muss in der Queue bleiben")
        #expect(await env.api.serverLineCount == 1)

        // Zweiter Versuch: derselbe Command wird nochmal gesendet.
        await env.engine.syncNow()

        #expect(await env.api.orderLineCallCount == 2)
        #expect(await env.api.serverLineCount == 1, "idempotent auf der Client-UUID")
        #expect(env.store.openPendingCount == 0)
        #expect(env.store.lines(forTable: 5).count == 1)
        #expect(env.openTotal(table: 5) == Money(cents: 880))
    }

    @Test("Ein 4xx-Command blockiert die Queue nicht dauerhaft")
    func clientErrorDoesNotBlockQueue() async throws {
        let env = try TestEnvironment()
        env.seedCatalog()

        let bier = try env.article("b1")
        let krainer = try env.article("a1")
        env.store.addLines(tableNumber: 2, items: [(bier, 1)])
        env.store.addLines(tableNumber: 2, items: [(krainer, 1)])
        #expect(env.store.openPendingCount == 2)

        await env.api.setNextOrderLinesError(.server(status: 400, reason: "kaputt"))
        await env.engine.syncNow()

        #expect(env.store.failedPendingCount == 1, "der defekte Command bleibt als fehlgeschlagen liegen")
        #expect(env.store.openPendingCount == 0, "der zweite Command wurde trotzdem gesendet")
        #expect(await env.api.serverLineCount == 1)

        let failed = env.store.pendingCommands().first(where: \.failedPermanently)
        #expect(failed?.lastError != nil)
    }

    @Test("Verworfener Command verschwindet aus der Queue")
    func discardingFailedCommand() async throws {
        let env = try TestEnvironment()
        env.seedCatalog()

        let bier = try env.article("b1")
        env.store.addLines(tableNumber: 2, items: [(bier, 1)])
        await env.api.setNextOrderLinesError(.server(status: 400, reason: "kaputt"))
        await env.engine.syncNow()

        let failedCommands = env.store.pendingCommands().filter(\.failedPermanently)
        let failed = try #require(failedCommands.first)
        env.store.discardCommand(id: failed.id)

        #expect(env.store.pendingCommands().isEmpty)
    }
}
