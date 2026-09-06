import Foundation
import KassaShared
import Testing
import VaporTesting
@testable import KassaServer

@Suite("Geräteliste")
struct DeviceTests {
    @Test("Ohne Token gibt es 401")
    func requiresToken() async throws {
        try await withKassaApp { harness in
            let response = try await harness.send(.GET, APIRoute.devices, token: nil)
            #expect(response.status == .unauthorized)
        }
    }

    @Test("Liefert die angemeldeten Geräte alphabetisch sortiert")
    func listsRegisteredDevices() async throws {
        try await withKassaApp { harness in
            let anna = try await harness.login(deviceName: "iPhone Anna")
            _ = try await harness.login(deviceName: "iPhone Bernd")

            let response = try await harness.send(.GET, APIRoute.devices, token: anna.token)
            #expect(response.status == .ok)

            let devices = try harness.decode([DeviceDTO].self, from: response)
            #expect(devices.map(\.name) == ["iPhone Anna", "iPhone Bernd"])
            #expect(devices.map(\.id).contains(anna.deviceId))
        }
    }

    @Test("Der Token-Hash verlässt die Antwort nie")
    func neverLeaksTokenHash() async throws {
        try await withKassaApp { harness in
            let login = try await harness.login(deviceName: "Schank")

            let response = try await harness.send(.GET, APIRoute.devices, token: login.token)
            #expect(response.status == .ok)

            // Bewusst am rohen JSON-String geprüft, nicht am dekodierten DTO —
            // ein zusätzliches Feld im DTO würde eine reine Decoder-Prüfung nicht bemerken.
            let raw = String(decoding: Data(buffer: response.body), as: UTF8.self)
            #expect(!raw.contains("tokenHash"))
            #expect(!raw.contains("token_hash"))
            let device = try #require(try await Device.find(login.deviceId, on: harness.db))
            #expect(!raw.contains(device.tokenHash))
        }
    }
}
