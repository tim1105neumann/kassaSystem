import KassaShared
import SwiftData
import SwiftUI

@main
struct KassaApp: App {
    @State private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    init() {
        _model = State(initialValue: AppModel())
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(model.store)
                .environment(model.settings)
                .modelContainer(model.store.container)
                .task { await model.startup() }
                .onChange(of: scenePhase) { _, phase in
                    // Mobilfunkverbindungen brechen still weg — beim Zurückkommen
                    // in den Vordergrund immer frisch synchronisieren.
                    if phase == .active { model.syncSoon() }
                }
        }
    }
}

/// Kompositionswurzel: hält Container, Settings, Client und Sync-Engine
/// zusammen und stellt der UI die wenigen Aktionen bereit, die das Netz
/// anstoßen.
@MainActor
@Observable
final class AppModel {
    let settings: AppSettings
    let store: LocalStore
    private let client: APIClient
    private let engine: SyncEngine

    var loginError: String?
    var isLoggingIn = false

    convenience init() {
        self.init(container: Self.makeContainer(), settings: AppSettings())
    }

    init(container: ModelContainer, settings: AppSettings) {
        let store = LocalStore(container: container, settings: settings)
        let client = APIClient()

        self.settings = settings
        self.store = store
        self.client = client
        self.engine = SyncEngine(store: store, api: client)
    }

    private static func makeContainer() -> ModelContainer {
        let schema = Schema([LocalArticle.self, LocalOrderLine.self, LocalSettlement.self, PendingCommand.self])
        if let container = try? ModelContainer(for: schema) { return container }
        // Ein beschädigter Store darf die App nicht startunfähig machen —
        // lieber ohne Persistenz weiterarbeiten als gar nicht.
        if let container = try? ModelContainer(
            for: schema,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        ) { return container }
        fatalError("ModelContainer konnte nicht erstellt werden")
    }

    func startup() async {
        await applyConfiguration()
        guard settings.isLoggedIn else {
            store.connection = .needsLogin
            return
        }
        await engine.refreshCatalog()
        await engine.start()
    }

    private func applyConfiguration() async {
        await client.configure(baseURL: settings.serverURL, token: settings.token)
    }

    func syncSoon() {
        Task { await engine.syncNow() }
    }

    func login(password: String) async {
        isLoggingIn = true
        loginError = nil
        defer { isLoggingIn = false }

        guard settings.serverURL != nil else {
            loginError = String(localized: "Bitte zuerst eine Server-Adresse eintragen.")
            return
        }
        await client.configure(baseURL: settings.serverURL, token: nil)
        do {
            let response = try await client.login(password: password, deviceName: settings.deviceName)
            settings.apply(.init(token: response.token, deviceId: response.deviceId))
            store.connection = .syncing
            await applyConfiguration()
            await engine.refreshCatalog()
            await engine.start()
            await engine.syncNow()
        } catch let error as APIError {
            loginError = error.germanMessage
        } catch {
            loginError = error.localizedDescription
        }
    }

    func logout() async {
        await engine.stop()
        settings.logout()
        store.wipeAll()
        store.connection = .needsLogin
        await applyConfiguration()
    }

    func applyServerURLChange() async {
        await engine.stop()
        await applyConfiguration()
        guard settings.isLoggedIn else { return }
        await engine.start()
    }

    func dayReport(for businessDay: String) async throws -> DayReportDTO {
        try await client.dayReport(businessDay: businessDay)
    }

    func discardCommand(id: UUID) {
        store.discardCommand(id: id)
    }
}
