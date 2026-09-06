import Foundation

/// Konfiguration des Küchendruckers, als JSON-Datei neben dem Programm.
/// Alles außer Serveradresse und Passwort hat einen Standardwert, damit die
/// Datei am Zielrechner nur zwei Zeilen lang sein muss.
public struct BonConfig: Codable, Sendable, Equatable {
    public var serverURL: String
    public var password: String
    public var deviceName: String
    /// CUPS-Queue, über die der Rohdatenstrom an den Drucker geht.
    public var printerQueue: String
    /// Nur Artikel aus dieser Kategorie landen auf dem Küchenbon.
    public var foodCategory: String
    /// Exakte Artikelnamen, die trotz Kategorie „Speisen" nicht gekocht werden.
    public var excludedArticles: [String]
    public var pollIntervalSeconds: Double
    /// Zeilen, die beim ersten Sehen schon älter sind, gelten als Nachzügler
    /// eines Netzausfalls — das Essen ist längst serviert.
    public var maxBonAgeMinutes: Int
    /// Notausgang, falls die Munbyn-Firmware CP437 nicht beherrscht.
    public var asciiFallback: Bool
    public var lineWidth: Int

    public init(
        serverURL: String,
        password: String,
        deviceName: String = "Kuechendrucker",
        printerQueue: String = "Kuechenbon",
        foodCategory: String = "Speisen",
        excludedArticles: [String] = ["Kaffee", "Mehlspeise"],
        pollIntervalSeconds: Double = 2,
        maxBonAgeMinutes: Int = 120,
        asciiFallback: Bool = false,
        lineWidth: Int = 48
    ) {
        self.serverURL = serverURL
        self.password = password
        self.deviceName = deviceName
        self.printerQueue = printerQueue
        self.foodCategory = foodCategory
        self.excludedArticles = excludedArticles
        self.pollIntervalSeconds = pollIntervalSeconds
        self.maxBonAgeMinutes = maxBonAgeMinutes
        self.asciiFallback = asciiFallback
        self.lineWidth = lineWidth
    }

    /// Fehlende Schlüssel sind kein Fehler, sondern der Standardwert — sonst
    /// müsste jede neue Option von Hand in die Datei am Mac nachgetragen werden.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let fallback = BonConfig(serverURL: "", password: "")
        serverURL = try container.decode(String.self, forKey: .serverURL)
        password = try container.decode(String.self, forKey: .password)
        deviceName = try container.decodeIfPresent(String.self, forKey: .deviceName) ?? fallback.deviceName
        printerQueue = try container.decodeIfPresent(String.self, forKey: .printerQueue) ?? fallback.printerQueue
        foodCategory = try container.decodeIfPresent(String.self, forKey: .foodCategory) ?? fallback.foodCategory
        excludedArticles = try container.decodeIfPresent([String].self, forKey: .excludedArticles) ?? fallback.excludedArticles
        pollIntervalSeconds = try container.decodeIfPresent(Double.self, forKey: .pollIntervalSeconds) ?? fallback.pollIntervalSeconds
        maxBonAgeMinutes = try container.decodeIfPresent(Int.self, forKey: .maxBonAgeMinutes) ?? fallback.maxBonAgeMinutes
        asciiFallback = try container.decodeIfPresent(Bool.self, forKey: .asciiFallback) ?? fallback.asciiFallback
        lineWidth = try container.decodeIfPresent(Int.self, forKey: .lineWidth) ?? fallback.lineWidth
    }

    /// Die Konfigdatei ist kein Wire-Format, deshalb bewusst ein normaler
    /// Decoder und nicht `KassaJSON.decoder`.
    public static func load(from path: String) throws -> BonConfig {
        let data = try Data(contentsOf: URL(fileURLWithPath: path))
        return try JSONDecoder().decode(BonConfig.self, from: data)
    }
}
