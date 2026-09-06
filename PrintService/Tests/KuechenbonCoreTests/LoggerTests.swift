import Foundation
import Testing
@testable import KuechenbonCore

@Suite("Logbuch")
struct LoggerTests {

    @Test("Überschreitet das Log die Grenze, entsteht genau eine Altdatei")
    func rotation() throws {
        let pfad = testPfad("kuechenbon.log")
        // Kleine Grenze statt 1 MB: derselbe Code, nur ohne 1 MB Testdaten.
        let log = Logger(path: pfad, maxBytes: 500, mirrorToStdout: false)

        for nummer in 1...40 {
            log.info("Bon \(nummer) gedruckt (Tisch 7, 3 Positionen).")
        }

        let dateien = FileManager.default
        #expect(dateien.fileExists(atPath: pfad))
        #expect(dateien.fileExists(atPath: pfad + ".1"))
        // Eine Altdatei, nicht mehr — .2 darf es nie geben.
        #expect(dateien.fileExists(atPath: pfad + ".2") == false)

        let ordner = (pfad as NSString).deletingLastPathComponent
        let inhalt = try dateien.contentsOfDirectory(atPath: ordner)
        #expect(inhalt.count == 2)

        let groesse = (try dateien.attributesOfItem(atPath: pfad)[.size] as? Int) ?? 0
        #expect(groesse <= 500)
    }

    @Test("Jede Zeile trägt Zeitstempel und Stufe")
    func zeilenformat() throws {
        let pfad = testPfad("kuechenbon.log")
        let log = Logger(path: pfad, mirrorToStdout: false)
        log.info("Dienst gestartet.")
        log.warn("Unbekannter Artikel a-neu.")
        log.error("Papier leer.")

        let zeilen = try String(contentsOfFile: pfad, encoding: .utf8)
            .split(separator: "\n")
            .map(String.init)
        #expect(zeilen.count == 3)
        #expect(zeilen[0].contains("[INFO] Dienst gestartet."))
        #expect(zeilen[1].contains("[WARN] Unbekannter Artikel a-neu."))
        #expect(zeilen[2].contains("[FEHLER] Papier leer."))
        // `06.09.2025 19:42:03 [INFO] …`
        #expect(zeilen[0].range(of: #"^\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2} \["#, options: .regularExpression) != nil)
    }
}
