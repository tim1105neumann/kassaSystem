import Foundation
import KassaShared

/// Alles, was der Dienst vom Server braucht. Als Protokoll, damit die
/// Dienstschleife im Test ohne Netz läuft — mehr Zweck hat es nicht.
public protocol KassaServerAccess: AnyObject {
    /// Wird nach dem Lauf in die Zustandsdatei zurückgeschrieben. Ohne das
    /// legt jeder Neustart über `POST /auth/login` ein weiteres Gerät am
    /// Server an, und die Geräteliste füllt sich mit Karteileichen.
    var token: String? { get }
    var deviceId: String? { get }

    func articles() throws -> [ArticleDTO]
    func devices() throws -> [DeviceDTO]
    func sync(since: Int) throws -> SyncResponse
}

public enum HTTPError: Error, LocalizedError, Equatable {
    case invalidURL(String)
    case noConnection(String)
    case timedOut
    case wrongPassword
    /// Auch nach einer frischen Anmeldung noch 401 — daran ist nichts zu retten.
    case notAuthorized
    case serverError(status: Int, reason: String?)
    case badResponse(String)

    public var errorDescription: String? {
        switch self {
        case .invalidURL(let text):
            return "Die Serveradresse „\(text)“ ist keine gültige URL. Bitte serverURL in config.json prüfen."
        case .noConnection(let grund):
            return "Keine Verbindung zum Kassaserver: \(grund)"
        case .timedOut:
            return "Der Kassaserver hat nicht innerhalb von 15 Sekunden geantwortet."
        case .wrongPassword:
            return "Der Kassaserver lehnt das Passwort ab. Bitte password in config.json prüfen."
        case .notAuthorized:
            return "Der Kassaserver verweigert den Zugriff, auch nach einer neuen Anmeldung."
        case .serverError(let status, let reason):
            if let reason { return "Der Kassaserver meldet Fehler \(status): \(reason)" }
            return "Der Kassaserver meldet Fehler \(status)."
        case .badResponse(let grund):
            return "Die Antwort des Kassaservers war unverständlich: \(grund)"
        }
    }
}

/// Synchroner HTTP-Client. Bewusst ohne async/await: das Binary läuft auf
/// macOS 11, wo die Swift-Nebenläufigkeit nur über Back-Deployment-
/// Bibliotheken verfügbar wäre, die ein nacktes Kommandozeilenprogramm
/// nicht mitbringen kann (siehe Kommentar in build-release.sh).
public final class HTTPClient: KassaServerAccess {
    private let baseURL: String
    private let password: String
    private let deviceName: String
    private let session: URLSession

    public private(set) var token: String?
    public private(set) var deviceId: String?

    public init(config: BonConfig, token: String?, deviceId: String?) throws {
        let getrimmt = config.serverURL.hasSuffix("/") ? String(config.serverURL.dropLast()) : config.serverURL
        guard let geprueft = URL(string: getrimmt), geprueft.host != nil else {
            throw HTTPError.invalidURL(config.serverURL)
        }
        self.baseURL = getrimmt
        self.password = config.password
        self.deviceName = config.deviceName
        self.token = token
        self.deviceId = deviceId

        let einstellungen = URLSessionConfiguration.ephemeral
        einstellungen.timeoutIntervalForRequest = 15
        einstellungen.timeoutIntervalForResource = 30
        // Der Dienst pollt ohnehin gleich wieder — auf eine Verbindung zu
        // warten, die es gerade nicht gibt, verzögert nur den nächsten Versuch.
        einstellungen.waitsForConnectivity = false
        self.session = URLSession(configuration: einstellungen)
    }

    // MARK: - Endpunkte

    public func login() throws {
        var request = try anfrage(pfad: APIRoute.login)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try KassaJSON.encoder.encode(
            LoginRequest(password: password, deviceName: deviceName)
        )

        let (daten, antwort) = try ausfuehren(request)
        if antwort.statusCode == 401 { throw HTTPError.wrongPassword }
        try pruefeStatus(antwort, daten: daten)

        let ergebnis: LoginResponse = try entschluessle(daten)
        token = ergebnis.token
        deviceId = ergebnis.deviceId
    }

    public func articles() throws -> [ArticleDTO] {
        try angemeldet(pfad: APIRoute.articles)
    }

    public func devices() throws -> [DeviceDTO] {
        try angemeldet(pfad: APIRoute.devices)
    }

    public func sync(since: Int) throws -> SyncResponse {
        try angemeldet(pfad: "\(APIRoute.sync)?since=\(since)")
    }

    // MARK: - Anfragen mit Token

    /// Ein 401 bedeutet nicht zwingend „falsches Passwort“, sondern meistens,
    /// dass das Gerät am Server ausgetragen wurde und der gespeicherte Token
    /// ins Leere zeigt. Deshalb genau ein Anmeldeversuch und genau eine
    /// Wiederholung — mehr wäre eine Endlosschleife gegen einen Server, der
    /// uns schlicht nicht mag.
    private func angemeldet<T: Decodable>(pfad: String) throws -> T {
        if token == nil { try login() }

        var request = try anfrage(pfad: pfad)
        request.setValue("Bearer \(token ?? "")", forHTTPHeaderField: "Authorization")
        var (daten, antwort) = try ausfuehren(request)

        if antwort.statusCode == 401 {
            try login()
            request.setValue("Bearer \(token ?? "")", forHTTPHeaderField: "Authorization")
            (daten, antwort) = try ausfuehren(request)
            if antwort.statusCode == 401 { throw HTTPError.notAuthorized }
        }

        try pruefeStatus(antwort, daten: daten)
        return try entschluessle(daten)
    }

    private func anfrage(pfad: String) throws -> URLRequest {
        guard let url = URL(string: baseURL + "/" + pfad) else {
            throw HTTPError.invalidURL(baseURL + "/" + pfad)
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        return request
    }

    private func pruefeStatus(_ antwort: HTTPURLResponse, daten: Data) throws {
        guard !(200..<300).contains(antwort.statusCode) else { return }
        let grund = try? KassaJSON.decoder.decode(APIErrorDTO.self, from: daten).reason
        throw HTTPError.serverError(status: antwort.statusCode, reason: grund)
    }

    private func entschluessle<T: Decodable>(_ daten: Data) throws -> T {
        do {
            return try KassaJSON.decoder.decode(T.self, from: daten)
        } catch {
            throw HTTPError.badResponse(error.localizedDescription)
        }
    }

    // MARK: - Der synchrone Kern

    /// Sammelt das Ergebnis des Completion-Handlers ein. Der Handler läuft auf
    /// einem Thread der URLSession, der Aufrufer wartet auf dem seinen —
    /// deshalb die Sperre; `@unchecked Sendable`, weil der Compiler diese
    /// Zusicherung nicht selbst nachrechnen kann.
    private final class AntwortBox: @unchecked Sendable {
        private let sperre = NSLock()
        private var inhalt: (Data?, URLResponse?, Error?) = (nil, nil, nil)

        func setze(_ daten: Data?, _ antwort: URLResponse?, _ fehler: Error?) {
            sperre.lock()
            inhalt = (daten, antwort, fehler)
            sperre.unlock()
        }

        func lies() -> (Data?, URLResponse?, Error?) {
            sperre.lock()
            defer { sperre.unlock() }
            return inhalt
        }
    }

    private func ausfuehren(_ request: URLRequest) throws -> (Data, HTTPURLResponse) {
        let box = AntwortBox()
        let semaphor = DispatchSemaphore(value: 0)
        let aufgabe = session.dataTask(with: request) { daten, antwort, fehler in
            box.setze(daten, antwort, fehler)
            semaphor.signal()
        }
        aufgabe.resume()
        // Ohne Zeitlimit warten ist hier gefahrlos: die URLSession bricht die
        // Anfrage nach 15 Sekunden selbst ab und ruft den Handler auf.
        semaphor.wait()

        let (daten, antwort, fehler) = box.lies()
        if let fehler = fehler as? URLError {
            if fehler.code == .timedOut { throw HTTPError.timedOut }
            throw HTTPError.noConnection(fehler.localizedDescription)
        }
        if let fehler { throw HTTPError.noConnection(fehler.localizedDescription) }

        guard let http = antwort as? HTTPURLResponse else {
            throw HTTPError.badResponse("keine HTTP-Antwort")
        }
        return (daten ?? Data(), http)
    }
}
