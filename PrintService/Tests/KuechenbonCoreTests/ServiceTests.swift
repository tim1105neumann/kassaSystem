import Foundation
import KassaShared
import Testing
@testable import KuechenbonCore

// MARK: - Ersatz für Netz und Drucker

/// Dieselben Geräte wie in Support.swift, nur in der Form, die der Server liefert.
let testGeraete = geraete.map { DeviceDTO(id: $0.key, name: $0.value) }

/// Server-Ersatz. Zählt mit, wie oft Katalog und Geräte geholt wurden — daran
/// hängt der Test, dass ein unbekannter Artikel genau ein Nachladen auslöst.
final class TestServer: KassaServerAccess {
    var token: String? = "test-token"
    var deviceId: String? = "d-drucker"

    var antwort: SyncResponse
    var katalog: [ArticleDTO]
    var geraete: [DeviceDTO]

    private(set) var artikelAufrufe = 0
    private(set) var geraeteAufrufe = 0
    private(set) var syncAufrufe = 0

    init(
        antwort: SyncResponse,
        katalog: [ArticleDTO] = Array(Katalog.alle.values),
        geraete: [DeviceDTO] = testGeraete
    ) {
        self.antwort = antwort
        self.katalog = katalog
        self.geraete = geraete
    }

    func articles() throws -> [ArticleDTO] {
        artikelAufrufe += 1
        return katalog
    }

    func devices() throws -> [DeviceDTO] {
        geraeteAufrufe += 1
        return geraete
    }

    func sync(since: Int) throws -> SyncResponse {
        syncAufrufe += 1
        return antwort
    }
}

final class TestDrucker: BonPrinter {
    private(set) var gedruckt: [BonJob] = []
    /// Bon mit dieser Nummer schlägt fehl — steht für leeres Papier.
    var scheitertBeiBon: Int?

    struct PapierLeer: Error, LocalizedError {
        var errorDescription: String? { "Papier leer" }
    }

    func print(_ job: BonJob, config: BonConfig) throws {
        if job.bonNumber == scheitertBeiBon { throw PapierLeer() }
        gedruckt.append(job)
    }
}

func testPfad(_ name: String) -> String {
    let ordner = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try? FileManager.default.createDirectory(at: ordner, withIntermediateDirectories: true)
    return ordner.appendingPathComponent(name).path
}

func stillesLog() -> Logger {
    Logger(path: testPfad("test.log"), mirrorToStdout: false)
}

func syncAntwort(_ zeilen: [OrderLineDTO], maxSeq: Int = 99) -> SyncResponse {
    SyncResponse(lines: zeilen, settlements: [], maxSeq: maxSeq, isFullReload: false)
}

// MARK: - Tests

@Suite("Dienstschleife")
struct ServiceTests {

    @Test("Scheitert der zweite Bon, bleibt der erste gesichert und lastSeq stehen")
    func teildruck() throws {
        let jetzt = wienerZeit(6, 19, 0)
        let delta = [
            zeile(Katalog.krainer, tisch: 3, createdAt: jetzt.addingTimeInterval(-60)),
            zeile(Katalog.bratwurst, tisch: 5, createdAt: jetzt.addingTimeInterval(-30))
        ]
        let server = TestServer(antwort: syncAntwort(delta))
        let drucker = TestDrucker()
        drucker.scheitertBeiBon = 2
        let zustandsPfad = testPfad("state.json")

        let dienst = Service(
            config: testConfig,
            state: PrintState(lastSeq: 10, nextBonNumber: 1),
            statePath: zustandsPfad,
            server: server,
            printer: drucker,
            log: stillesLog()
        )

        #expect(dienst.runOnce(now: jetzt) == false)

        let gesichert = try PrintState.load(from: zustandsPfad)
        #expect(drucker.gedruckt.map(\.tableNumber) == [3])
        #expect(gesichert.lines[delta[0].id]?.status == .printed)
        #expect(gesichert.lines[delta[1].id] == nil)
        // lastSeq bleibt stehen, damit dasselbe Delta erneut kommt.
        #expect(gesichert.lastSeq == 10)
        // Der abgebrochene Bon behält seine Nummer.
        #expect(gesichert.nextBonNumber == 2)
    }

    @Test("Im nächsten Durchlauf wird nur der fehlende Bon nachgeholt, mit derselben Nummer")
    func nachholen() throws {
        let jetzt = wienerZeit(6, 19, 0)
        let delta = [
            zeile(Katalog.krainer, tisch: 3, createdAt: jetzt.addingTimeInterval(-60)),
            zeile(Katalog.bratwurst, tisch: 5, createdAt: jetzt.addingTimeInterval(-30))
        ]
        let server = TestServer(antwort: syncAntwort(delta))
        let drucker = TestDrucker()
        drucker.scheitertBeiBon = 2
        let zustandsPfad = testPfad("state.json")

        func neuerDienst(_ zustand: PrintState) -> Service {
            Service(
                config: testConfig,
                state: zustand,
                statePath: zustandsPfad,
                server: server,
                printer: drucker,
                log: stillesLog()
            )
        }

        _ = neuerDienst(PrintState(lastSeq: 10, nextBonNumber: 1)).runOnce(now: jetzt)

        // Papier nachgelegt, Dienst neu gestartet, dasselbe Delta.
        drucker.scheitertBeiBon = nil
        let zweiter = neuerDienst(try PrintState.load(from: zustandsPfad))
        #expect(zweiter.runOnce(now: jetzt) == true)

        #expect(drucker.gedruckt.count == 2)
        #expect(drucker.gedruckt[1].tableNumber == 5)
        #expect(drucker.gedruckt[1].bonNumber == 2)

        let gesichert = try PrintState.load(from: zustandsPfad)
        #expect(gesichert.lastSeq == 99)
        #expect(gesichert.nextBonNumber == 3)
        #expect(gesichert.lines[delta[1].id]?.status == .printed)
    }

    @Test("Ein unbekannter Artikel lädt den Katalog genau einmal neu und wird danach gedruckt")
    func katalogNachladen() throws {
        let jetzt = wienerZeit(6, 19, 0)
        let server = TestServer(antwort: syncAntwort([
            zeile(Katalog.krainer, tisch: 1, createdAt: jetzt)
        ]))
        let drucker = TestDrucker()

        let dienst = Service(
            config: testConfig,
            state: PrintState(lastSeq: 10, nextBonNumber: 1),
            statePath: nil,
            server: server,
            printer: drucker,
            log: stillesLog()
        )
        #expect(dienst.runOnce(now: jetzt) == true)
        #expect(server.artikelAufrufe == 1)

        // Neu importierte Speise: sie steht im Delta, aber noch nicht im
        // zwischengespeicherten Katalog.
        let neu = ArticleDTO(id: "a-neu", category: "Speisen", name: "Backhendl", priceCents: 1290, sortOrder: 9, active: true)
        server.katalog.append(neu)
        server.antwort = syncAntwort([zeile(neu, tisch: 2, createdAt: jetzt)], maxSeq: 120)

        #expect(dienst.runOnce(now: jetzt) == true)
        #expect(server.artikelAufrufe == 2)
        #expect(drucker.gedruckt.last?.items == [BonJob.Item(qty: 1, name: "Backhendl")])

        // Der dritte Durchlauf kennt den Artikel schon und fragt nicht erneut.
        server.antwort = syncAntwort([zeile(neu, tisch: 3, createdAt: jetzt)], maxSeq: 130)
        #expect(dienst.runOnce(now: jetzt) == true)
        #expect(server.artikelAufrufe == 2)
    }

    @Test("Ein unbekanntes Gerät lädt die Geräteliste einmal nach und der Kellnername steht auf dem Bon")
    func geraeteNachladen() throws {
        let jetzt = wienerZeit(6, 19, 0)
        let server = TestServer(antwort: syncAntwort([
            zeile(Katalog.krainer, tisch: 1, createdAt: jetzt, deviceId: "d-anna")
        ]))
        let drucker = TestDrucker()
        let dienst = Service(
            config: testConfig,
            state: PrintState(lastSeq: 10, nextBonNumber: 1),
            statePath: nil,
            server: server,
            printer: drucker,
            log: stillesLog()
        )

        #expect(dienst.runOnce(now: jetzt) == true)
        #expect(server.geraeteAufrufe == 1)
        #expect(drucker.gedruckt.first?.deviceName == "iPhone Anna")

        server.antwort = syncAntwort([zeile(Katalog.krainer, tisch: 2, createdAt: jetzt, deviceId: "d-anna")], maxSeq: 120)
        #expect(dienst.runOnce(now: jetzt) == true)
        #expect(server.geraeteAufrufe == 1)
    }

    @Test("lastSeq 0 ist ein Kaltstart: nichts wird gedruckt, erst der nächste Durchlauf druckt")
    func kaltstart() throws {
        let jetzt = wienerZeit(6, 19, 0)
        let alt = zeile(Katalog.krainer, tisch: 1, createdAt: jetzt.addingTimeInterval(-60))
        let server = TestServer(antwort: syncAntwort([alt], maxSeq: 50))
        let drucker = TestDrucker()
        let zustandsPfad = testPfad("state.json")

        let dienst = Service(
            config: testConfig,
            state: PrintState(),
            statePath: zustandsPfad,
            server: server,
            printer: drucker,
            log: stillesLog()
        )
        #expect(dienst.runOnce(now: jetzt) == true)
        #expect(drucker.gedruckt.isEmpty)

        let nachKaltstart = try PrintState.load(from: zustandsPfad)
        #expect(nachKaltstart.lines[alt.id]?.status == .ignored)
        #expect(nachKaltstart.lastSeq == 50)

        // Jetzt ist lastSeq gesetzt — die nächste Bestellung ist echt.
        let neu = zeile(Katalog.bratwurst, tisch: 2, createdAt: jetzt)
        server.antwort = syncAntwort([neu], maxSeq: 60)
        let zweiter = Service(
            config: testConfig,
            state: nachKaltstart,
            statePath: zustandsPfad,
            server: server,
            printer: drucker,
            log: stillesLog()
        )
        #expect(zweiter.runOnce(now: jetzt) == true)
        #expect(drucker.gedruckt.count == 1)
        #expect(drucker.gedruckt[0].tableNumber == 2)
    }

    @Test("Ein Probelauf verändert die Zustandsdatei nicht")
    func probelaufOhneFolgen() throws {
        let jetzt = wienerZeit(6, 19, 0)
        let server = TestServer(antwort: syncAntwort([zeile(Katalog.krainer, tisch: 1, createdAt: jetzt)]))
        let zustandsPfad = testPfad("state.json")

        let dienst = Service(
            config: testConfig,
            state: PrintState(lastSeq: 10, nextBonNumber: 1),
            statePath: nil,
            server: server,
            printer: DryRunPrinter(),
            log: stillesLog()
        )
        #expect(dienst.runOnce(now: jetzt) == true)
        #expect(FileManager.default.fileExists(atPath: zustandsPfad) == false)
    }
}
