import Foundation
import KassaShared
import NIOConcurrencyHelpers
import Testing
import VaporTesting
@testable import KassaServer

@Suite("WebSocket")
struct WebSocketTests {
    @Test("Nach einer Mutation bekommen verbundene Clients einen SyncPing")
    func broadcastsAfterMutation() async throws {
        try await withApp { app in
            try await configure(app, kassa: KassaConfig(
                password: testPassword,
                tableCount: 40,
                cutoffHour: 6,
                storage: .memory,
                port: 0,
                priceListPath: nil
            ))
            let harness = Harness(app: app, tester: try app.testing())
            try await harness.importPriceList()
            let token = try await harness.login().token
            let kaffee = try await harness.article(named: "Kaffee", token: token)

            try await app.server.start(address: .hostname("127.0.0.1", port: 0))
            let port = try #require(app.http.server.shared.localAddress?.port)

            let (stream, continuation) = AsyncStream<String>.makeStream()
            var headers = HTTPHeaders()
            headers.bearerAuthorization = .init(token: token)
            let clientSocket = NIOLockedValueBox<WebSocket?>(nil)
            try await WebSocket.connect(
                to: "ws://127.0.0.1:\(port)/\(APIRoute.webSocket)",
                headers: headers,
                on: app.eventLoopGroup
            ) { socket in
                clientSocket.withLockedValue { $0 = socket }
                socket.onText { _, text in continuation.yield(text) }
            }

            // Der Upgrade ist clientseitig fertig, bevor der Server den Socket
            // registriert hat — sonst geht der erste Ping ins Leere.
            for _ in 0..<100 where await app.sockets.count == 0 {
                try await Task.sleep(for: .milliseconds(20))
            }
            #expect(await app.sockets.count == 1)

            try await harness.book(
                [NewOrderLine(id: UUID(), tableNumber: 1, articleId: kaffee.id, qty: 1, createdAt: Date())],
                token: token
            )

            let text = await withTaskGroup(of: String?.self) { group in
                group.addTask {
                    for await text in stream { return text }
                    return nil
                }
                group.addTask {
                    try? await Task.sleep(for: .seconds(5))
                    return nil
                }
                let first = await group.next() ?? nil
                group.cancelAll()
                return first
            }

            let ping = try KassaJSON.decoder.decode(SyncPing.self, from: Data(try #require(text).utf8))
            #expect(ping.seq == (try await harness.sync(since: 0, token: token).maxSeq))

            try await clientSocket.withLockedValue { $0 }?.close()
            await app.server.shutdown()
        }
    }
}
