import Foundation
import KassaShared

actor APIClient: KassaAPI {
    private var baseURL: URL?
    private var token: String?
    private let session: URLSession

    private let encoder = KassaJSON.encoder
    private let decoder = KassaJSON.decoder

    init(baseURL: URL? = nil, token: String? = nil) {
        self.baseURL = baseURL
        self.token = token
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        config.waitsForConnectivity = false
        self.session = URLSession(configuration: config)
    }

    func configure(baseURL: URL?, token: String?) {
        self.baseURL = baseURL
        self.token = token
    }

    // MARK: - Endpunkte

    func login(password: String, deviceName: String) async throws -> LoginResponse {
        try await send(
            route: APIRoute.login,
            method: "POST",
            body: try encode(LoginRequest(password: password, deviceName: deviceName)),
            requiresToken: false
        )
    }

    func config() async throws -> ServerConfigDTO {
        try await send(route: APIRoute.config, method: "GET")
    }

    func articles() async throws -> [ArticleDTO] {
        try await send(route: APIRoute.articles, method: "GET")
    }

    func sync(since: Int) async throws -> SyncResponse {
        try await send(route: APIRoute.sync, method: "GET", query: [URLQueryItem(name: "since", value: "\(since)")])
    }

    func createOrderLines(_ request: CreateOrderLinesRequest) async throws {
        try await sendRaw(route: APIRoute.orderLines, method: "POST", body: try encode(request))
    }

    func voidLine(id: UUID) async throws {
        try await sendRaw(route: APIRoute.voidLine(id), method: "POST")
    }

    func createSettlement(_ request: CreateSettlementRequest) async throws -> SettlementDTO {
        try await send(route: APIRoute.settlements, method: "POST", body: try encode(request))
    }

    func dayReport(businessDay: String) async throws -> DayReportDTO {
        try await send(
            route: APIRoute.dayReport,
            method: "GET",
            query: [URLQueryItem(name: "date", value: businessDay)]
        )
    }

    // MARK: - WebSocket

    func syncPings() async -> AsyncStream<SyncPing> {
        guard let baseURL, let token, let url = webSocketURL(from: baseURL) else {
            return AsyncStream { $0.finish() }
        }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let task = session.webSocketTask(with: request)
        let localDecoder = decoder

        return AsyncStream { continuation in
            let receiver = Task {
                task.resume()
                while !Task.isCancelled {
                    do {
                        let message = try await task.receive()
                        let data: Data? = switch message {
                        case .data(let value): value
                        case .string(let value): value.data(using: .utf8)
                        @unknown default: nil
                        }
                        if let data, let ping = try? localDecoder.decode(SyncPing.self, from: data) {
                            continuation.yield(ping)
                        }
                    } catch {
                        break
                    }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in
                receiver.cancel()
                task.cancel(with: .goingAway, reason: nil)
            }
        }
    }

    private func webSocketURL(from base: URL) -> URL? {
        var components = URLComponents(url: base.appending(path: APIRoute.webSocket), resolvingAgainstBaseURL: false)
        components?.scheme = base.scheme == "http" ? "ws" : "wss"
        return components?.url
    }

    // MARK: - Transport

    private func encode(_ value: some Encodable) throws -> Data {
        do {
            return try encoder.encode(value)
        } catch {
            throw APIError.decoding(error)
        }
    }

    private func send<Response: Decodable>(
        route: String,
        method: String,
        query: [URLQueryItem] = [],
        body: Data? = nil,
        requiresToken: Bool = true
    ) async throws -> Response {
        let data = try await sendRaw(route: route, method: method, query: query, body: body, requiresToken: requiresToken)
        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            throw APIError.decoding(error)
        }
    }

    @discardableResult
    private func sendRaw(
        route: String,
        method: String,
        query: [URLQueryItem] = [],
        body: Data? = nil,
        requiresToken: Bool = true
    ) async throws -> Data {
        guard let baseURL else { throw APIError.notConfigured }
        if requiresToken, token == nil { throw APIError.notConfigured }

        var components = URLComponents(url: baseURL.appending(path: route), resolvingAgainstBaseURL: false)
        if !query.isEmpty { components?.queryItems = query }
        guard let url = components?.url else { throw APIError.notConfigured }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw APIError.transport(error)
        }

        guard let http = response as? HTTPURLResponse else {
            throw APIError.server(status: -1, reason: nil)
        }

        switch http.statusCode {
        case 200...299:
            return data
        case 401, 403:
            throw APIError.unauthorized
        case 409:
            if let conflict = try? decoder.decode(SettlementConflictDTO.self, from: data) {
                throw APIError.settlementConflict(conflict)
            }
            throw APIError.server(status: 409, reason: reason(from: data))
        default:
            throw APIError.server(status: http.statusCode, reason: reason(from: data))
        }
    }

    private func reason(from data: Data) -> String? {
        (try? decoder.decode(APIErrorDTO.self, from: data))?.reason
    }
}
