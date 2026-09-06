import Foundation
import Testing
@testable import KuechenbonCore

@Suite("Bonlayout")
struct BonRendererTests {

    private let bestellung = BonJob(
        kind: .order,
        tableNumber: 5,
        bonNumber: 47,
        time: wienerZeit(6, 19, 42),
        deviceName: "iPhone Anna",
        items: [
            BonJob.Item(qty: 2, name: "Käsekrainer mit Gebäck"),
            BonJob.Item(qty: 1, name: "Bratwurst mit Pommes")
        ]
    )

    @Test("Der Bestell-Bon zeigt Tisch, Bonnummer, Zeit, Kellner und alle Positionen")
    func bestellbonLayout() {
        let text = BonRenderer.plainText(bestellung, config: testConfig)
        let zeilen = text.components(separatedBy: "\n")

        #expect(zeilen[0] == String(repeating: "=", count: 48))
        #expect(zeilen[1].contains("T I S C H  5"))
        #expect(zeilen[3].hasPrefix("Bon 47"))
        #expect(zeilen[3].hasSuffix("Sa 06.09.  19:42"))
        #expect(zeilen[4] == "Kellner: iPhone Anna")
        #expect(zeilen.contains(" 2x  Käsekrainer mit Gebäck"))
        #expect(zeilen.contains(" 1x  Bratwurst mit Pommes"))
    }

    @Test("Umlaute bleiben im Klartext erhalten und keine Zeile ist länger als lineWidth")
    func keineZeileZuLang() {
        let text = BonRenderer.plainText(bestellung, config: testConfig)
        #expect(text.contains("Käsekrainer"))
        for zeile in text.components(separatedBy: "\n") {
            #expect(zeile.count <= testConfig.lineWidth, "Zu lang: \(zeile)")
        }
    }

    @Test("Ein langer Artikelname wird eingerückt umgebrochen statt abgeschnitten")
    func langerNameWirdUmgebrochen() {
        let job = BonJob(
            kind: .order,
            tableNumber: 5,
            bonNumber: 1,
            time: wienerZeit(6, 19, 42),
            deviceName: nil,
            items: [BonJob.Item(qty: 1, name: "Große Käsekrainer mit Gebäck und Senf und Kren extra scharf")]
        )
        let zeilen = BonRenderer.plainText(job, config: testConfig).components(separatedBy: "\n")

        let positionen = zeilen.filter { $0.contains("Käsekrainer") || $0.contains("scharf") }
        #expect(positionen.count == 2)
        #expect(positionen[0].hasPrefix(" 1x  "))
        #expect(positionen[1].hasPrefix("     "))
        for zeile in zeilen { #expect(zeile.count <= 48) }

        // Nichts darf verloren gehen.
        let zusammen = positionen.map { $0.trimmingCharacters(in: .whitespaces) }
            .joined(separator: " ")
            .replacingOccurrences(of: "1x  ", with: "")
        #expect(zusammen == "Große Käsekrainer mit Gebäck und Senf und Kren extra scharf")
    }

    @Test("Ohne bekanntes Gerät fehlt die Kellnerzeile")
    func ohneKellnerzeile() {
        var job = bestellung
        job.deviceName = nil
        #expect(BonRenderer.plainText(job, config: testConfig).contains("Kellner") == false)
    }

    @Test("Der Storno-Bon nennt Storno, Tisch und die ursprünglichen Bonnummern")
    func stornobonLayout() {
        let job = BonJob(
            kind: .cancellation,
            tableNumber: 5,
            bonNumber: 48,
            time: wienerZeit(6, 19, 55),
            deviceName: "iPhone Anna",
            items: [BonJob.Item(qty: 3, name: "Käsekrainer mit Gebäck")],
            originalBonNumbers: [45, 46]
        )
        let text = BonRenderer.plainText(job, config: testConfig)

        #expect(text.contains("*** S T O R N O ***"))
        #expect(text.contains("TISCH 5"))
        #expect(text.contains("(war auf Bon 45, 46)"))
        #expect(text.contains("T I S C H") == false)
    }

    @Test("Ein einzelner ursprünglicher Bon steht in der Einzahl da")
    func stornoMitEinemUrsprungsbon() {
        var job = BonJob(
            kind: .cancellation,
            tableNumber: 5,
            bonNumber: 48,
            time: wienerZeit(6, 19, 55),
            deviceName: nil,
            items: [BonJob.Item(qty: 1, name: "Bratwurst mit Pommes")],
            originalBonNumbers: [45]
        )
        #expect(BonRenderer.plainText(job, config: testConfig).contains("(war auf Bon 45)"))

        job.originalBonNumbers = []
        #expect(BonRenderer.plainText(job, config: testConfig).contains("war auf Bon") == false)
    }

    @Test("Die ESC/POS-Ausgabe rahmt das Layout mit Initialisierung, Codepage und Abschnitt")
    func escPosSteuerbefehle() {
        let bytes = [UInt8](BonRenderer.escPos(bestellung, config: testConfig))

        #expect(Array(bytes.prefix(2)) == [0x1B, 0x40])              // ESC @
        #expect(Array(bytes[2..<5]) == [0x1B, 0x74, 0x00])           // ESC t 0
        #expect(Array(bytes[5..<8]) == [0x1B, 0x61, 0x00])           // ESC a 0
        #expect(Array(bytes.suffix(4)) == [0x1D, 0x56, 0x42, 0x00])  // GS V 66 0
        #expect(Array(bytes.suffix(8).prefix(4)) == [0x0A, 0x0A, 0x0A, 0x0A])
        #expect(enthaelt(bytes, [0x1D, 0x21, 0x11]))                 // Tischzeile doppelt groß
        #expect(enthaelt(bytes, [0x1D, 0x21, 0x00]))                 // und wieder zurück
        #expect(enthaelt(bytes, [0x4B, 0x84, 0x73]))                 // "Käs" in CP437
    }

    @Test("Die doppelt große Tischzeile wird auf die halbe Spaltenzahl zentriert")
    func tischzeileZentriertAufHalberBreite() {
        let zeilen = BonRenderer.plainText(bestellung, config: testConfig).components(separatedBy: "\n")
        let tisch = zeilen[1]
        let text = tisch.trimmingCharacters(in: .whitespaces)
        #expect(tisch.count - text.count == (24 - text.count) / 2)
    }

    private func enthaelt(_ heuhaufen: [UInt8], _ nadel: [UInt8]) -> Bool {
        guard heuhaufen.count >= nadel.count else { return false }
        for start in 0...(heuhaufen.count - nadel.count) where Array(heuhaufen[start..<start + nadel.count]) == nadel {
            return true
        }
        return false
    }
}
