import Fluent
import Vapor

struct KassaConfig: Sendable {
    enum Storage: Sendable {
        case file(String)
        case memory
    }

    var password: String
    var tableCount: Int
    var cutoffHour: Int
    var storage: Storage
    var port: Int
    /// `nil` schaltet den automatischen Erstimport ab (Tests importieren selbst).
    var priceListPath: String?
    /// Wie weit `paidAt` vom Gerät von der Serverzeit abweichen darf, bevor die
    /// Serverzeit gewinnt. Siehe `plausiblePaidAt`. Bewusst keine ENV-Variable —
    /// nur die Tests setzen das um, um historische Daten anlegen zu können.
    var paidAtTolerance: TimeInterval = 24 * 60 * 60

    static func fromEnvironment() throws -> KassaConfig {
        guard let password = Environment.get("KASSA_PASSWORD"), !password.isEmpty else {
            throw StartupError("KASSA_PASSWORD ist nicht gesetzt — Start abgebrochen.")
        }
        return KassaConfig(
            password: password,
            tableCount: Environment.get("TABLE_COUNT").flatMap(Int.init) ?? 40,
            cutoffHour: Environment.get("BUSINESS_DAY_CUTOFF_HOUR").flatMap(Int.init) ?? 6,
            storage: .file(Environment.get("DATABASE_PATH") ?? "/data/kassa.sqlite"),
            port: Environment.get("PORT").flatMap(Int.init) ?? 8080,
            priceListPath: Environment.get("PRICE_LIST_PATH") ?? "preisliste.csv"
        )
    }
}

struct StartupError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

private struct KassaConfigKey: StorageKey {
    typealias Value = KassaConfig
}

private struct SocketHubKey: StorageKey {
    typealias Value = SocketHub
}

private struct WriteLockKey: StorageKey {
    typealias Value = WriteLock
}

extension Application {
    var kassa: KassaConfig {
        get {
            guard let config = storage[KassaConfigKey.self] else {
                fatalError("configure(_:kassa:) wurde nicht aufgerufen")
            }
            return config
        }
        set { storage[KassaConfigKey.self] = newValue }
    }

    var sockets: SocketHub {
        if let hub = storage[SocketHubKey.self] { return hub }
        let hub = SocketHub()
        storage[SocketHubKey.self] = hub
        return hub
    }

    var writeLock: WriteLock {
        if let lock = storage[WriteLockKey.self] { return lock }
        let lock = WriteLock()
        storage[WriteLockKey.self] = lock
        return lock
    }
}

extension Request {
    var kassa: KassaConfig { application.kassa }
}
