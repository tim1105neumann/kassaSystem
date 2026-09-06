import Foundation
import KassaShared
@testable import Kassa

/// Minimaler Fake-Server im Speicher. Idempotent auf den client-vergebenen
/// UUIDs — genau wie der echte Server, damit ein doppelt gesendeter Command
/// keine Doppelbuchung erzeugt.
actor FakeKassaAPI: KassaAPI {
    private var articleCatalog: [ArticleDTO]
    private var lines: [UUID: OrderLineDTO] = [:]
    private var settlements: [UUID: SettlementDTO] = [:]
    private var seq = 0

    /// Alle Aufrufe scheitern mit einem Netzfehler.
    var offline = false
    /// Wendet den nächsten Buchungs-Command an und wirft danach trotzdem einen
    /// Netzfehler — die verlorene Antwort, die zum erneuten Senden führt.
    var loseNextResponse = false
    var nextOrderLinesError: APIError?
    var nextSettlementError: APIError?

    private(set) var orderLineCallCount = 0
    private(set) var settlementCallCount = 0
    private(set) var voidCallCount = 0

    init(articles: [ArticleDTO] = FakeKassaAPI.defaultArticles) {
        self.articleCatalog = articles
    }

    static let defaultArticles: [ArticleDTO] = [
        ArticleDTO(id: "a1", category: "Speisen", name: "Käsekrainer mit Gebäck", priceCents: 620, sortOrder: 0, active: true),
        ArticleDTO(id: "b1", category: "Getraenke", name: "Bier, Radler 0,5 l", priceCents: 440, sortOrder: 10, active: true)
    ]

    func setOffline(_ value: Bool) { offline = value }
    func setLoseNextResponse(_ value: Bool) { loseNextResponse = value }
    func setNextOrderLinesError(_ value: APIError?) { nextOrderLinesError = value }
    func setNextSettlementError(_ value: APIError?) { nextSettlementError = value }

    var serverLineCount: Int { lines.count }
    func serverLine(id: UUID) -> OrderLineDTO? { lines[id] }
    var serverSettlementCount: Int { settlements.count }

    // MARK: - KassaAPI

    func login(password: String, deviceName: String) async throws -> LoginResponse {
        try guardOnline()
        return LoginResponse(token: "test-token", deviceId: "fake-device")
    }

    func config() async throws -> ServerConfigDTO {
        try guardOnline()
        return ServerConfigDTO(tableCount: 40, businessDayCutoffHour: 6, catalogVersion: 1)
    }

    func articles() async throws -> [ArticleDTO] {
        try guardOnline()
        return articleCatalog
    }

    func sync(since: Int) async throws -> SyncResponse {
        try guardOnline()
        return SyncResponse(
            lines: lines.values.filter { $0.updatedSeq > since }.sorted { $0.updatedSeq < $1.updatedSeq },
            settlements: settlements.values.filter { $0.updatedSeq > since }.sorted { $0.updatedSeq < $1.updatedSeq },
            maxSeq: seq,
            isFullReload: false
        )
    }

    func createOrderLines(_ request: CreateOrderLinesRequest) async throws {
        orderLineCallCount += 1
        try guardOnline()
        if let error = nextOrderLinesError {
            nextOrderLinesError = nil
            throw error
        }

        for new in request.lines where lines[new.id] == nil {
            guard let article = articleCatalog.first(where: { $0.id == new.articleId }) else {
                throw APIError.server(status: 400, reason: "unbekannter Artikel")
            }
            seq += 1
            lines[new.id] = OrderLineDTO(
                id: new.id,
                tableNumber: new.tableNumber,
                articleId: new.articleId,
                nameSnapshot: article.name,
                unitPriceCents: article.priceCents,
                qty: new.qty,
                createdAt: new.createdAt,
                deviceId: "fake-device",
                updatedSeq: seq
            )
        }

        if loseNextResponse {
            loseNextResponse = false
            throw APIError.transport(URLError(.networkConnectionLost))
        }
    }

    func voidLine(id: UUID) async throws {
        voidCallCount += 1
        try guardOnline()
        guard var line = lines[id] else { throw APIError.server(status: 404, reason: "unbekannte Zeile") }
        if line.voidedAt == nil {
            seq += 1
            line.voidedAt = .now
            line.updatedSeq = seq
            lines[id] = line
        }
    }

    func createSettlement(_ request: CreateSettlementRequest) async throws -> SettlementDTO {
        settlementCallCount += 1
        try guardOnline()
        if let error = nextSettlementError {
            nextSettlementError = nil
            throw error
        }
        if let existing = settlements[request.id] { return existing }

        for selection in request.lines {
            guard var line = lines[selection.lineId] else { continue }
            seq += 1
            line.settlementId = request.id
            line.updatedSeq = seq
            lines[selection.lineId] = line
        }
        seq += 1
        let settlement = SettlementDTO(
            id: request.id,
            tableNumber: request.tableNumber,
            totalCents: request.amountCents,
            paidAt: request.paidAt,
            deviceId: "fake-device",
            businessDay: BusinessDay.day(for: request.paidAt, cutoffHour: 6),
            updatedSeq: seq,
            tipCents: request.tipCents
        )
        settlements[request.id] = settlement
        return settlement
    }

    func dayReport(businessDay: String) async throws -> DayReportDTO {
        try guardOnline()
        let matching = settlements.values.filter { $0.businessDay == businessDay }
        return DayReportDTO(
            businessDay: businessDay,
            totalCents: matching.reduce(0) { $0 + $1.totalCents },
            settlementCount: matching.count,
            byCategory: [],
            topArticles: [],
            settlements: matching.sorted { $0.paidAt < $1.paidAt },
            tipCents: matching.reduce(0) { $0 + $1.tipCents }
        )
    }

    func syncPings() async -> AsyncStream<SyncPing> {
        AsyncStream { $0.finish() }
    }

    private func guardOnline() throws {
        if offline { throw APIError.transport(URLError(.notConnectedToInternet)) }
    }
}
