import Foundation
import Observation

/// Server-URL, Gerätename und der Sync-Zeiger. Nichts Sensibles — das Token
/// liegt in der Keychain.
@MainActor
@Observable
final class AppSettings {
    private let defaults: UserDefaults
    private static let tokenKey = "bearerToken"

    var serverURLString: String {
        didSet { defaults.set(serverURLString, forKey: "serverURL") }
    }

    var deviceName: String {
        didSet { defaults.set(deviceName, forKey: "deviceName") }
    }

    private(set) var deviceId: String {
        didSet { defaults.set(deviceId, forKey: "deviceId") }
    }

    /// Höchste vom Server bestätigte Sequenznummer.
    var maxSeq: Int {
        didSet { defaults.set(maxSeq, forKey: "maxSeq") }
    }

    var tableCount: Int {
        didSet { defaults.set(tableCount, forKey: "tableCount") }
    }

    var businessDayCutoffHour: Int {
        didSet { defaults.set(businessDayCutoffHour, forKey: "cutoffHour") }
    }

    var catalogVersion: Int {
        didSet { defaults.set(catalogVersion, forKey: "catalogVersion") }
    }

    private(set) var token: String?

    var isLoggedIn: Bool { token != nil && serverURL != nil }

    var serverURL: URL? {
        let trimmed = serverURLString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let url = URL(string: trimmed), url.scheme != nil else { return nil }
        return url
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.serverURLString = defaults.string(forKey: "serverURL") ?? ""
        self.deviceName = defaults.string(forKey: "deviceName") ?? "iPhone"
        let storedDeviceId = defaults.string(forKey: "deviceId") ?? UUID().uuidString
        self.deviceId = storedDeviceId
        defaults.set(storedDeviceId, forKey: "deviceId")
        self.maxSeq = defaults.integer(forKey: "maxSeq")
        let storedTableCount = defaults.integer(forKey: "tableCount")
        self.tableCount = storedTableCount > 0 ? storedTableCount : 40
        let storedCutoff = defaults.object(forKey: "cutoffHour") as? Int
        self.businessDayCutoffHour = storedCutoff ?? 6
        self.catalogVersion = defaults.integer(forKey: "catalogVersion")
        self.token = Keychain.string(for: Self.tokenKey)
    }

    func apply(_ login: LoginPayload) {
        token = login.token
        deviceId = login.deviceId
        Keychain.set(login.token, for: Self.tokenKey)
    }

    func logout() {
        token = nil
        maxSeq = 0
        Keychain.remove(Self.tokenKey)
    }

    struct LoginPayload: Sendable {
        var token: String
        var deviceId: String
    }
}
