import Fluent
import Foundation
import KassaShared
import NIOCore
import Testing
import VaporTesting
@testable import KassaServer

let testPassword = "heuriger-geheim"

/// Repo-Wurzel relativ zu dieser Datei — unabhängig davon, aus welchem
/// Verzeichnis `swift test` gestartet wurde.
let repositoryRoot = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent() // ServerTests
    .deletingLastPathComponent() // Tests
    .deletingLastPathComponent() // Server
    .deletingLastPathComponent() // kassaSystemHeuriger

let priceListURL = repositoryRoot.appendingPathComponent("preisliste.csv")

struct Harness: Sendable {
    let app: Application
    let tester: any TestingApplicationTester

    var db: any Database { app.db }

    // MARK: HTTP

    func send(
        _ method: HTTPMethod,
        _ path: String,
        token: String?,
        body: (any Encodable)? = nil
    ) async throws -> TestingHTTPResponse {
        try await tester.sendRequest(method, path) { request in
            if let token { request.headers.bearerAuthorization = .init(token: token) }
            if let body {
                request.headers.contentType = .json
                request.body = ByteBuffer(data: try KassaJSON.encoder.encode(body))
            }
        }
    }

    func decode<T: Decodable>(_ type: T.Type, from response: TestingHTTPResponse) throws -> T {
        try KassaJSON.decoder.decode(type, from: Data(buffer: response.body))
    }

    // MARK: Bequemlichkeiten

    func login(deviceName: String = "Testgerät", password: String = testPassword) async throws -> LoginResponse {
        let response = try await send(.POST, APIRoute.login, token: nil, body: LoginRequest(password: password, deviceName: deviceName))
        #expect(response.status == .ok)
        return try decode(LoginResponse.self, from: response)
    }

    func importPriceList(from url: URL = priceListURL) async throws {
        try await PriceList.importFile(at: url.path, on: app.db, lock: app.writeLock)
    }

    func articles(token: String) async throws -> [ArticleDTO] {
        let response = try await send(.GET, APIRoute.articles, token: token)
        #expect(response.status == .ok)
        return try decode([ArticleDTO].self, from: response)
    }

    func article(named name: String, token: String) async throws -> ArticleDTO {
        let all = try await articles(token: token)
        return try #require(all.first { $0.name == name })
    }

    @discardableResult
    func book(
        _ lines: [NewOrderLine],
        token: String,
        expect status: HTTPResponseStatus = .ok
    ) async throws -> TestingHTTPResponse {
        let response = try await send(.POST, APIRoute.orderLines, token: token, body: CreateOrderLinesRequest(lines: lines))
        #expect(response.status == status)
        return response
    }

    func sync(since: Int, token: String) async throws -> SyncResponse {
        let response = try await send(.GET, "\(APIRoute.sync)?since=\(since)", token: token)
        #expect(response.status == .ok)
        return try decode(SyncResponse.self, from: response)
    }
}

func withKassaApp(
    tableCount: Int = 40,
    cutoffHour: Int = 6,
    paidAtTolerance: TimeInterval = 24 * 60 * 60,
    _ body: (Harness) async throws -> Void
) async throws {
    try await withApp { app in
        try await configure(app, kassa: KassaConfig(
            password: testPassword,
            tableCount: tableCount,
            cutoffHour: cutoffHour,
            storage: .memory,
            port: 8080,
            priceListPath: nil, // Tests importieren gezielt selbst
            paidAtTolerance: paidAtTolerance
        ))
        try await body(Harness(app: app, tester: try app.testing()))
    }
}

func viennaDate(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = BusinessDay.timeZone
    var components = DateComponents()
    components.year = year
    components.month = month
    components.day = day
    components.hour = hour
    components.minute = minute
    return calendar.date(from: components)!
}
