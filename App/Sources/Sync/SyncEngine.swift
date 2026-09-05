import Foundation
import KassaShared

/// Arbeitet die Offline-Queue ab, zieht danach das Delta und hält den
/// WebSocket. Alles, was persistiert wird, geht über den `LocalStore`.
actor SyncEngine {
    private let store: LocalStore
    private let api: any KassaAPI

    private var pollTask: Task<Void, Never>?
    private var socketTask: Task<Void, Never>?
    private var isSyncing = false
    /// Backoff für den Poll-Loop: bei Netzproblemen nicht alle 10 s hämmern.
    private var backoffSeconds: Int = 0

    private static let pollInterval = 10
    private static let maxBackoff = 60

    init(store: LocalStore, api: any KassaAPI) {
        self.store = store
        self.api = api
    }

    // MARK: - Lebenszyklus

    func start() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.syncNow()
                guard let delay = await self?.currentInterval() else { return }
                try? await Task.sleep(for: .seconds(delay))
            }
        }
        socketTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                for await _ in await self.api.syncPings() {
                    await self.syncNow()
                }
                // Verbindung weg — kurz warten, dann neu aufbauen.
                try? await Task.sleep(for: .seconds(5))
            }
        }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
        socketTask?.cancel()
        socketTask = nil
    }

    private func currentInterval() -> Int {
        max(Self.pollInterval, backoffSeconds)
    }

    // MARK: - Sync

    func syncNow() async {
        guard !isSyncing else { return }
        isSyncing = true
        defer { isSyncing = false }

        await store.setConnection(.syncing)

        let queueResult = await drainQueue()
        if case .paused(let error) = queueResult {
            await report(error)
            return
        }

        do {
            let since = await store.currentMaxSeq
            let response = try await api.sync(since: since)
            await store.merge(response)
            await store.setConnection(.connected)
            await store.clearError()
            backoffSeconds = 0
        } catch let error as APIError {
            await report(error)
        } catch {
            await report(.transport(error))
        }
    }

    /// Lädt Konfiguration und Katalog. Nach dem Login und beim Kaltstart.
    func refreshCatalog() async {
        do {
            let config = try await api.config()
            await store.applyConfig(config)
            let articles = try await api.articles()
            await store.replaceArticles(articles)
        } catch let error as APIError {
            await report(error)
        } catch {
            await report(.transport(error))
        }
    }

    /// Kompletten Spiegel neu ziehen — nach einem Konflikt beim Kassieren.
    func forceFullReload() async {
        do {
            let response = try await api.sync(since: 0)
            await store.merge(SyncResponse(
                lines: response.lines,
                settlements: response.settlements,
                maxSeq: response.maxSeq,
                isFullReload: true
            ))
            await store.setConnection(.connected)
        } catch let error as APIError {
            await report(error)
        } catch {
            await report(.transport(error))
        }
    }

    // MARK: - Queue

    private enum QueueResult {
        case drained
        /// Netz- oder Auth-Problem: Reihenfolge muss erhalten bleiben, also
        /// hier abbrechen und beim nächsten Lauf weitermachen.
        case paused(APIError)
    }

    private func drainQueue() async -> QueueResult {
        while let command = await store.nextPendingCommand() {
            do {
                try await send(command)
                await store.completeCommand(id: command.id)
            } catch let error as APIError {
                switch error.disposition {
                case .retry:
                    await store.recordFailure(id: command.id, message: error.germanMessage, permanent: false)
                    return .paused(error)
                case .needsLogin:
                    await store.recordFailure(id: command.id, message: error.germanMessage, permanent: false)
                    return .paused(error)
                case .permanent:
                    await handlePermanentFailure(command, error: error)
                }
            } catch {
                await store.recordFailure(id: command.id, message: error.localizedDescription, permanent: false)
                return .paused(.transport(error))
            }
        }
        return .drained
    }

    private func handlePermanentFailure(_ command: PendingCommandSnapshot, error: APIError) async {
        if command.kind == .settle {
            let tableNumber = decodedSettlement(command)?.tableNumber
            let message: String = if case .settlementConflict(let conflict) = error, let tableNumber {
                conflict.germanMessage(tableNumber: tableNumber)
            } else {
                error.germanMessage
            }
            await store.reportConflict(message)
            // Lokal als kassiert markierte Zeilen zurücknehmen und danach den
            // Serverstand neu holen — auf keinen Fall stillschweigend erneut buchen.
            if let request = decodedSettlement(command) {
                await store.rollbackSettlement(id: request.id)
            }
            await store.completeCommand(id: command.id)
            await forceFullReload()
            return
        }
        await store.recordFailure(id: command.id, message: error.germanMessage, permanent: true)
    }

    private func decodedSettlement(_ command: PendingCommandSnapshot) -> CreateSettlementRequest? {
        try? KassaJSON.decoder.decode(CreateSettlementRequest.self, from: command.payload)
    }

    private func send(_ command: PendingCommandSnapshot) async throws {
        let decoder = KassaJSON.decoder
        switch command.kind {
        case .addLines:
            let request: CreateOrderLinesRequest
            do {
                request = try decoder.decode(CreateOrderLinesRequest.self, from: command.payload)
            } catch {
                throw APIError.decoding(error)
            }
            try await api.createOrderLines(request)
        case .voidLine:
            let request: VoidLineCommand
            do {
                request = try decoder.decode(VoidLineCommand.self, from: command.payload)
            } catch {
                throw APIError.decoding(error)
            }
            try await api.voidLine(id: request.lineId)
        case .settle:
            let request: CreateSettlementRequest
            do {
                request = try decoder.decode(CreateSettlementRequest.self, from: command.payload)
            } catch {
                throw APIError.decoding(error)
            }
            _ = try await api.createSettlement(request)
        }
    }

    // MARK: - Status

    private func report(_ error: APIError) async {
        if case .needsLogin = error.disposition {
            await store.setConnection(.needsLogin)
        } else {
            await store.setConnection(.offline)
        }
        await store.setError(error.germanMessage)
        if error.isOffline {
            backoffSeconds = min(max(backoffSeconds * 2, Self.pollInterval * 2), Self.maxBackoff)
        }
    }
}

// MARK: - Store-Zugriffe des Actors

extension LocalStore {
    var currentMaxSeq: Int { settings.maxSeq }

    func setConnection(_ state: ConnectionState) {
        connection = state
    }

    func setError(_ message: String?) {
        lastErrorMessage = message
    }

    func clearError() {
        lastErrorMessage = nil
    }

    func reportConflict(_ message: String) {
        conflictMessage = message
    }

    func applyConfig(_ config: ServerConfigDTO) {
        settings.tableCount = config.tableCount
        settings.businessDayCutoffHour = config.businessDayCutoffHour
        settings.catalogVersion = config.catalogVersion
    }
}
