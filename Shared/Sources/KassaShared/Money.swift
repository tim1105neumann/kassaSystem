import Foundation

/// Geldbetrag in ganzen Cent. Bewusst kein `Double` — Rundungsfehler haben in
/// einer Kassa nichts verloren.
public struct Money: Codable, Hashable, Sendable, Comparable {
    public var cents: Int

    public init(cents: Int) {
        self.cents = cents
    }

    public static let zero = Money(cents: 0)

    public static func < (lhs: Money, rhs: Money) -> Bool { lhs.cents < rhs.cents }
    public static func + (lhs: Money, rhs: Money) -> Money { Money(cents: lhs.cents + rhs.cents) }
    public static func - (lhs: Money, rhs: Money) -> Money { Money(cents: lhs.cents - rhs.cents) }
    public static func * (lhs: Money, rhs: Int) -> Money { Money(cents: lhs.cents * rhs) }

    public static func += (lhs: inout Money, rhs: Money) { lhs = lhs + rhs }

    /// Codiert als reine Cent-Zahl, damit über die Leitung nie ein Float geht.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        self.cents = try container.decode(Int.self)
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(cents)
    }
}

extension Money {
    /// Österreichische Schreibweise, z. B. `6,20 €`.
    public var formatted: String {
        let sign = cents < 0 ? "-" : ""
        let absolute = abs(cents)
        let euro = absolute / 100
        let rest = absolute % 100
        return "\(sign)\(euro),\(String(format: "%02d", rest)) €"
    }

    /// Ohne Währungszeichen, z. B. `6,20` — für Eingabefelder.
    public var formattedPlain: String {
        let sign = cents < 0 ? "-" : ""
        let absolute = abs(cents)
        return "\(sign)\(absolute / 100),\(String(format: "%02d", absolute % 100))"
    }

    /// Parst `6,20`, `6.20`, `6` oder `,50`. Gibt `nil` bei Unsinn zurück.
    public init?(parsing text: String) {
        let trimmed = text
            .trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: "€", with: "")
            .trimmingCharacters(in: .whitespaces)
            .replacingOccurrences(of: ",", with: ".")
        guard !trimmed.isEmpty else { return nil }

        let negative = trimmed.hasPrefix("-")
        let body = negative ? String(trimmed.dropFirst()) : trimmed
        let parts = body.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count <= 2 else { return nil }

        let euroPart = parts[0].isEmpty ? "0" : String(parts[0])
        guard let euro = Int(euroPart), euro >= 0 else { return nil }

        var centPart = 0
        if parts.count == 2 {
            let raw = String(parts[1])
            guard raw.count <= 2, raw.allSatisfy(\.isNumber) else { return nil }
            let padded = raw.padding(toLength: 2, withPad: "0", startingAt: 0)
            guard let parsed = Int(padded) else { return nil }
            centPart = parsed
        }

        let total = euro * 100 + centPart
        self.init(cents: negative ? -total : total)
    }
}
