import Foundation
import Testing
@testable import KuechenbonCore

@Suite("Zustand und Konfiguration")
struct StateTests {

    @Test("Einträge älter als vier Tage verschwinden, jüngere bleiben")
    func prunedWirftAltesWeg() {
        let jetzt = wienerZeit(6, 19, 42)
        let alt = UUID()
        let jung = UUID()
        let state = PrintState(lines: [
            alt: LineRecord(status: .printed, at: jetzt.addingTimeInterval(-5 * 24 * 60 * 60), bonNumber: 1),
            jung: LineRecord(status: .printed, at: jetzt.addingTimeInterval(-3 * 24 * 60 * 60), bonNumber: 2)
        ])

        let sauber = state.pruned(now: jetzt)
        #expect(sauber.lines[alt] == nil)
        #expect(sauber.lines[jung]?.bonNumber == 2)
        #expect(sauber.nextBonNumber == state.nextBonNumber)
    }

    @Test("Der Zustand überlebt Speichern und Laden unverändert")
    func zustandRundlauf() throws {
        let pfad = NSTemporaryDirectory() + "kuechenbon-state-\(UUID().uuidString).json"
        defer { try? FileManager.default.removeItem(atPath: pfad) }

        let state = PrintState(
            lastSeq: 812,
            nextBonNumber: 48,
            token: "t-123",
            deviceId: "d-anna",
            lines: [UUID(): LineRecord(status: .cancelled, at: wienerZeit(6, 19, 42), bonNumber: 45)]
        )
        try state.save(to: pfad)
        #expect(try PrintState.load(from: pfad) == state)
    }

    @Test("Eine fehlende Zustandsdatei ergibt den Startzustand")
    func fehlendeDateiIstKaltstart() throws {
        let state = try PrintState.load(from: NSTemporaryDirectory() + "gibt-es-nicht-\(UUID().uuidString).json")
        #expect(state.lastSeq == 0)
        #expect(state.nextBonNumber == 1)
        #expect(state.lines.isEmpty)
    }

    @Test("Die Konfigdatei braucht nur Serveradresse und Passwort")
    func konfigMitStandardwerten() throws {
        let pfad = NSTemporaryDirectory() + "kuechenbon-config-\(UUID().uuidString).json"
        defer { try? FileManager.default.removeItem(atPath: pfad) }
        try #"{"serverURL":"https://kassa.viennax.at","password":"geheim","lineWidth":32}"#
            .write(toFile: pfad, atomically: true, encoding: .utf8)

        let config = try BonConfig.load(from: pfad)
        #expect(config.serverURL == "https://kassa.viennax.at")
        #expect(config.password == "geheim")
        #expect(config.lineWidth == 32)
        #expect(config.deviceName == "Kuechendrucker")
        #expect(config.foodCategory == "Speisen")
        #expect(config.excludedArticles == ["Kaffee", "Mehlspeise"])
        #expect(config.maxBonAgeMinutes == 120)
        #expect(config.asciiFallback == false)
    }
}
