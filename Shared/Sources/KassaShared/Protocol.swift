import Foundation

/// Beide Seiten müssen Daten identisch codieren, sonst verschieben sich
/// Zeitstempel. Deshalb liegt die Konfiguration hier und nicht doppelt.
public enum KassaJSON {
    public static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }

    public static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}

/// Route-Konstanten an einer Stelle, damit App und Server nicht auseinanderlaufen.
public enum APIRoute {
    public static let login = "auth/login"
    public static let config = "config"
    public static let articles = "articles"
    public static let sync = "sync"
    public static let orderLines = "orders/lines"
    public static let settlements = "settlements"
    public static let dayReport = "reports/day"
    public static let webSocket = "ws"

    public static func voidLine(_ id: UUID) -> String { "orders/lines/\(id.uuidString)/void" }
}

/// Der Heuriger hat nach Mitternacht offen — ein Kalendertag würde den
/// Tagesabschluss mitten im Betrieb durchschneiden. Alles vor `cutoffHour`
/// zählt deshalb noch zum Vortag.
public enum BusinessDay {
    public static let timeZone = TimeZone(identifier: "Europe/Vienna") ?? .current

    public static func day(for date: Date, cutoffHour: Int) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let shifted = calendar.date(byAdding: .hour, value: -cutoffHour, to: date) ?? date
        let parts = calendar.dateComponents([.year, .month, .day], from: shifted)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// Zeitfenster `[start, end)` eines Betriebstags — für Report-Abfragen.
    public static func range(for day: String, cutoffHour: Int) -> (start: Date, end: Date)? {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        var components = DateComponents()
        components.year = parts[0]
        components.month = parts[1]
        components.day = parts[2]
        components.hour = cutoffHour
        guard let start = calendar.date(from: components),
              let end = calendar.date(byAdding: .day, value: 1, to: start) else { return nil }
        return (start, end)
    }
}
