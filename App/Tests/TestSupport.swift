import Foundation
import KassaShared
import SwiftData
import Testing
@testable import Kassa

@MainActor
struct TestEnvironment {
    let container: ModelContainer
    let settings: AppSettings
    let store: LocalStore
    let api: FakeKassaAPI
    let engine: SyncEngine

    init(api: FakeKassaAPI = FakeKassaAPI()) throws {
        let schema = Schema([LocalArticle.self, LocalOrderLine.self, LocalSettlement.self, PendingCommand.self])
        container = try ModelContainer(for: schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true))

        let suite = "kassa.tests.\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suite) else {
            throw TestSetupError.noDefaults
        }
        settings = AppSettings(defaults: defaults)
        store = LocalStore(container: container, settings: settings)
        self.api = api
        engine = SyncEngine(store: store, api: api)
    }

    /// Katalog lokal setzen, ohne den Fake-Server zu befragen.
    func seedCatalog() {
        store.replaceArticles(FakeKassaAPI.defaultArticles)
    }

    func article(_ id: String) throws -> LocalArticle {
        guard let article = store.articles().first(where: { $0.id == id }) else {
            throw TestSetupError.missingArticle(id)
        }
        return article
    }

    func openTotal(table: Int) -> Money {
        TableTotals.openTotal(of: store.lines(forTable: table))
    }
}

enum TestSetupError: Error {
    case noDefaults
    case missingArticle(String)
}
