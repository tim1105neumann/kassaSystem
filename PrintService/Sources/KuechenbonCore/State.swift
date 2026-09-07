import Foundation

/// Was mit einer Bestellzeile passiert ist. Die Entscheidung fällt genau
/// einmal und wird festgehalten, damit ein Neustart nichts doppelt druckt.
public struct LineRecord: Codable, Sendable, Equatable {
    public enum Status: String, Codable, Sendable { case printed, ignored, cancelled }

    public var status: Status
    public var at: Date
    /// Die Nummer des Bons, auf dem die Zeile stand. Bleibt beim Storno
    /// erhalten — der Storno-Bon verweist darauf zurück.
    public var bonNumber: Int?

    public init(status: Status, at: Date, bonNumber: Int? = nil) {
        self.status = status
        self.at = at
        self.bonNumber = bonNumber
    }
}

public struct PrintState: Codable, Sendable, Equatable {
    /// 0 = noch nie gelaufen; der erste Lauf ist damit ein Kaltstart.
    public var lastSeq: Int
    public var nextBonNumber: Int
    public var token: String?
    public var deviceId: String?
    public var lines: [UUID: LineRecord]
    /// Wann die Aufstellung zu diesem Druckauftrag aufs Papier ging. Der
    /// Druckauftrag kommt im nächsten Delta erneut (siehe Service.swift), und
    /// nur dieser Eintrag verhindert, dass der Gast einen zweiten Zettel bekommt.
    public var printRequests: [UUID: Date]

    public init(
        lastSeq: Int = 0,
        nextBonNumber: Int = 1,
        token: String? = nil,
        deviceId: String? = nil,
        lines: [UUID: LineRecord] = [:],
        printRequests: [UUID: Date] = [:]
    ) {
        self.lastSeq = lastSeq
        self.nextBonNumber = nextBonNumber
        self.token = token
        self.deviceId = deviceId
        self.lines = lines
        self.printRequests = printRequests
    }

    /// Von Hand statt synthetisiert: die Zustandsdatei am Küchen-Mac gibt es
    /// bereits, und in ihr fehlt `printRequests`. Der synthetisierte Decoder
    /// verlangt jedes nicht optionale Feld und würde `load` daran scheitern
    /// lassen — der Dienst startete nach dem Aufspielen des neuen Binaries gar
    /// nicht mehr, wegen eines Schlüssels, den es beim letzten Speichern noch
    /// nicht geben konnte.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        lastSeq = try container.decode(Int.self, forKey: .lastSeq)
        nextBonNumber = try container.decode(Int.self, forKey: .nextBonNumber)
        token = try container.decodeIfPresent(String.self, forKey: .token)
        deviceId = try container.decodeIfPresent(String.self, forKey: .deviceId)
        lines = try container.decode([UUID: LineRecord].self, forKey: .lines)
        printRequests = try container.decodeIfPresent([UUID: Date].self, forKey: .printRequests) ?? [:]
    }

    /// Der Server liefert beim Vollabgleich nur die letzten drei Betriebstage
    /// nach (`retentionStart` in Server/Sources/KassaServer/Routes.swift:131).
    /// Ältere Einträge können also nie wieder in einem Delta auftauchen und
    /// würden die Zustandsdatei nur endlos wachsen lassen.
    public func pruned(olderThan maxAge: TimeInterval = 4 * 24 * 60 * 60, now: Date) -> PrintState {
        var copy = self
        copy.lines = lines.filter { now.timeIntervalSince($0.value.at) <= maxAge }
        copy.printRequests = printRequests.filter { now.timeIntervalSince($0.value) <= maxAge }
        return copy
    }

    /// Eine fehlende Datei ist der Normalfall beim allerersten Start.
    public static func load(from path: String) throws -> PrintState {
        guard let data = FileManager.default.contents(atPath: path) else { return PrintState() }
        return try decoder.decode(PrintState.self, from: data)
    }

    public func save(to path: String) throws {
        try PrintState.encoder.encode(self).write(to: URL(fileURLWithPath: path), options: .atomic)
    }

    private static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }

    private static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }
}
