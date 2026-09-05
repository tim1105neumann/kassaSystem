import Foundation
import KassaShared
import Testing
import VaporTesting
@testable import KassaServer

@Suite("Authentifizierung")
struct AuthTests {
    @Test("Ohne Token gibt es 401")
    func requiresToken() async throws {
        try await withKassaApp { harness in
            for path in [APIRoute.config, APIRoute.articles, APIRoute.sync, APIRoute.dayReport] {
                let response = try await harness.send(.GET, path, token: nil)
                #expect(response.status == .unauthorized)
            }
        }
    }

    @Test("Falsches Passwort gibt 401 und legt kein Gerät an")
    func rejectsWrongPassword() async throws {
        try await withKassaApp { harness in
            let response = try await harness.send(
                .POST, APIRoute.login, token: nil,
                body: LoginRequest(password: "falsch", deviceName: "Angreifer")
            )
            #expect(response.status == .unauthorized)
            #expect(try await Device.query(on: harness.db).count() == 0)
        }
    }

    @Test("Erfundener Token gibt 401")
    func rejectsUnknownToken() async throws {
        try await withKassaApp { harness in
            let response = try await harness.send(.GET, APIRoute.config, token: "ausgedacht")
            #expect(response.status == .unauthorized)
        }
    }

    @Test("Login liefert einen Token, im Klartext gespeichert wird er nicht")
    func loginStoresOnlyHash() async throws {
        try await withKassaApp { harness in
            let login = try await harness.login(deviceName: "Schank")
            let device = try #require(try await Device.find(login.deviceId, on: harness.db))
            #expect(device.name == "Schank")
            #expect(device.tokenHash != login.token)
            #expect(device.tokenHash == DeviceToken.hash(login.token))

            let response = try await harness.send(.GET, APIRoute.config, token: login.token)
            #expect(response.status == .ok)
        }
    }

    @Test("Die Konfiguration kommt aus den Server-Einstellungen")
    func servesConfig() async throws {
        try await withKassaApp(tableCount: 12, cutoffHour: 5) { harness in
            let token = try await harness.login().token
            try await harness.importPriceList()
            let response = try await harness.send(.GET, APIRoute.config, token: token)
            let config = try harness.decode(ServerConfigDTO.self, from: response)
            #expect(config.tableCount == 12)
            #expect(config.businessDayCutoffHour == 5)
            #expect(config.catalogVersion == 1)
        }
    }
}
