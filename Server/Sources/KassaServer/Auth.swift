import Crypto
import Fluent
import Foundation
import Vapor

enum DeviceToken {
    /// 32 Byte Zufall, base64url — landet beim Client im Keychain.
    static func generate() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        for index in bytes.indices { bytes[index] = UInt8.random(in: .min ... .max) }
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// In der Datenbank liegt nur der Hash — ein Datenbank-Leak gibt keine Tokens preis.
    static func hash(_ token: String) -> String {
        SHA256.hash(data: Data(token.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// Vergleich über die Digests statt über die Strings: gleich lang und
    /// unabhängig davon, ab welchem Zeichen sich die Passwörter unterscheiden.
    static func passwordMatches(_ given: String, _ expected: String) -> Bool {
        SHA256.hash(data: Data(given.utf8)) == SHA256.hash(data: Data(expected.utf8))
    }
}

private struct DeviceKey: StorageKey {
    typealias Value = Device
}

extension Request {
    var device: Device {
        get throws {
            guard let device = storage[DeviceKey.self] else { throw Abort(.unauthorized) }
            return device
        }
    }
}

struct DeviceAuthMiddleware: AsyncMiddleware {
    func respond(to request: Request, chainingTo next: any AsyncResponder) async throws -> Response {
        guard let bearer = request.headers.bearerAuthorization else {
            throw Abort(.unauthorized, reason: "Kein Bearer-Token")
        }
        let hash = DeviceToken.hash(bearer.token)
        guard let device = try await Device.query(on: request.db)
            .filter(\.$tokenHash == hash)
            .first()
        else {
            throw Abort(.unauthorized, reason: "Token unbekannt")
        }

        // Grob nachführen, sonst wäre jeder Lesezugriff auch ein Schreibzugriff.
        if device.lastSeenAt.timeIntervalSinceNow < -60 {
            device.lastSeenAt = Date()
            let db = request.db
            try await request.application.writeLock.run { try await device.save(on: db) }
        }

        request.storage[DeviceKey.self] = device
        return try await next.respond(to: request)
    }
}
