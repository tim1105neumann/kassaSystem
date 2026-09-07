import Foundation
import KassaShared

/// Abstraktion über den Server, damit Tests einen Fake einsetzen können.
protocol KassaAPI: Sendable {
    func login(password: String, deviceName: String) async throws -> LoginResponse
    func config() async throws -> ServerConfigDTO
    func articles() async throws -> [ArticleDTO]
    func sync(since: Int) async throws -> SyncResponse
    func createOrderLines(_ request: CreateOrderLinesRequest) async throws
    func voidLine(id: UUID) async throws
    func createSettlement(_ request: CreateSettlementRequest) async throws -> SettlementDTO
    func createPrintRequest(_ request: CreatePrintRequestRequest) async throws
    func dayReport(businessDay: String) async throws -> DayReportDTO
    /// Push-Signal „es gibt Neues“. Der Stream endet, wenn die Verbindung
    /// abreißt; der Aufrufer baut sie dann neu auf.
    func syncPings() async -> AsyncStream<SyncPing>
}

enum APIError: Error {
    /// Server-URL oder Token fehlen — kein Netzfehler, sondern Einrichtung.
    case notConfigured
    case unauthorized
    case settlementConflict(SettlementConflictDTO)
    case server(status: Int, reason: String?)
    case transport(any Error)
    case decoding(any Error)
}

extension APIError {
    /// Wie die Offline-Queue mit dem Fehler umgeht.
    enum Disposition {
        /// Später erneut versuchen, Reihenfolge bleibt erhalten.
        case retry
        /// Wird sich nicht heilen — Command als fehlgeschlagen ablegen und
        /// mit dem nächsten weitermachen.
        case permanent
        /// Anmeldung nötig; die ganze Queue pausiert.
        case needsLogin
    }

    var disposition: Disposition {
        switch self {
        case .notConfigured, .transport:
            .retry
        case .unauthorized:
            .needsLogin
        case .settlementConflict:
            .permanent
        case .decoding:
            .permanent
        case .server(let status, _):
            // 408/429 sind vorübergehend, alles andere im 4xx-Bereich nicht.
            if status == 408 || status == 429 || status >= 500 { .retry } else { .permanent }
        }
    }

    var isOffline: Bool {
        switch self {
        case .transport, .notConfigured: true
        default: false
        }
    }

    var germanMessage: String {
        switch self {
        case .notConfigured:
            String(localized: "Server-URL oder Anmeldung fehlt. Bitte in den Einstellungen prüfen.")
        case .unauthorized:
            String(localized: "Anmeldung abgelaufen. Bitte neu anmelden.")
        case .settlementConflict(let conflict):
            conflict.germanMessage
        case .server(let status, let reason):
            reason.map { String(localized: "Server-Fehler \(status): \($0)") }
                ?? String(localized: "Server-Fehler \(status).")
        case .transport:
            String(localized: "Keine Verbindung zum Server.")
        case .decoding:
            String(localized: "Antwort des Servers nicht lesbar.")
        }
    }
}

extension SettlementConflictDTO {
    var germanMessage: String {
        switch reason {
        case .alreadySettled:
            if let device = settledByDevice {
                return String(localized: "Wurde bereits von \(device) kassiert.")
            }
            return String(localized: "Wurde bereits kassiert.")
        case .lineVoided:
            return String(localized: "Eine der Positionen wurde zwischenzeitlich storniert.")
        case .amountMismatch:
            return String(localized: "Der Betrag stimmt nicht mehr mit dem Serverstand überein.")
        case .unknownLine:
            return String(localized: "Eine der Positionen ist am Server nicht bekannt.")
        case .quantityExceeded:
            return String(localized: "Es wurden mehr Stück ausgewählt, als noch offen sind.")
        }
    }

    func germanMessage(tableNumber: Int) -> String {
        switch reason {
        case .alreadySettled:
            if let device = settledByDevice {
                return String(localized: "Tisch \(tableNumber) wurde bereits von \(device) kassiert.")
            }
            return String(localized: "Tisch \(tableNumber) wurde bereits kassiert.")
        default:
            return germanMessage
        }
    }
}
