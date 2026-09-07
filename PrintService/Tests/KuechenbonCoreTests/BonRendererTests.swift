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

    // MARK: - Aufstellung

    private let aufstellung = BonJob(
        kind: .overview,
        tableNumber: 7,
        bonNumber: nil,
        time: wienerZeit(6, 19, 42),
        deviceName: "Anna",
        items: [
            BonJob.Item(qty: 2, name: "Käsekrainer mit Gebäck", unitPriceCents: 620),
            BonJob.Item(qty: 3, name: "Bier, Radler 0,5 l", unitPriceCents: 440)
        ],
        totalCents: 2560
    )

    @Test("Die Aufstellung zeigt Kopf, Tisch, Kellner, Einzelbeträge, Summe und den Hinweis")
    func aufstellungLayout() {
        let text = BonRenderer.plainText(aufstellung, config: testConfig)
        let zeilen = text.components(separatedBy: "\n")

        #expect(zeilen[1].contains("A U F S T E L L U N G"))
        #expect(zeilen[2].contains("Tisch 7"))
        // Nicht das TISCH-Format der Küchenbons — sonst hält der Koch den
        // Zettel für eine Bestellung.
        #expect(text.contains("T I S C H") == false)
        #expect(zeilen[4].hasPrefix("Sa 06.09.  19:42"))
        #expect(zeilen[4].hasSuffix("Kellner: Anna"))
        #expect(text.contains("Bon ") == false)
        #expect(zeilen.contains { $0.hasPrefix(" 2x  Käsekrainer mit Gebäck") && $0.hasSuffix("12,40") })
        #expect(zeilen.contains { $0.hasPrefix(" 3x  Bier, Radler 0,5 l") && $0.hasSuffix("13,20") })
        #expect(text.contains("keine Rechnung"))
        for zeile in zeilen { #expect(zeile.count <= testConfig.lineWidth, "Zu lang: \(zeile)") }
    }

    @Test("Die Summe steht rechtsbündig in EUR und stimmt mit den Einzelbeträgen überein")
    func aufstellungSumme() throws {
        let zeilen = BonRenderer.plainText(aufstellung, config: testConfig).components(separatedBy: "\n")
        let summe = try #require(zeilen.first { $0.hasPrefix("SUMME") })

        #expect(summe.hasSuffix("25,60 EUR"))
        // Doppelt große Zeilen haben nur die halbe Spaltenzahl.
        #expect(summe.count == testConfig.lineWidth / 2)
        // Das Eurozeichen fehlt in CP437, deshalb ausgeschrieben.
        #expect(summe.contains("€") == false)
    }

    @Test("Ein langer Artikelname wird umgebrochen, der Betrag steht auf der letzten Zeile")
    func aufstellungLangerName() {
        var job = aufstellung
        job.items = [BonJob.Item(
            qty: 1,
            name: "Große Käsekrainer mit Gebäck und Senf und Kren extra scharf",
            unitPriceCents: 1290
        )]
        let zeilen = BonRenderer.plainText(job, config: testConfig).components(separatedBy: "\n")

        let positionen = zeilen.filter { $0.contains("Käsekrainer") || $0.contains("scharf") }
        #expect(positionen.count == 2)
        #expect(positionen[0].hasPrefix(" 1x  "))
        #expect(positionen[0].contains("12,90") == false)
        #expect(positionen[1].hasPrefix("     "))
        #expect(positionen[1].hasSuffix("12,90"))
        for zeile in zeilen { #expect(zeile.count <= 48, "Zu lang: \(zeile)") }

        // Nichts darf verloren gehen.
        let zusammen = positionen
            .map { $0.replacingOccurrences(of: "12,90", with: "").trimmingCharacters(in: .whitespaces) }
            .joined(separator: " ")
            .replacingOccurrences(of: "1x  ", with: "")
        #expect(zusammen == "Große Käsekrainer mit Gebäck und Senf und Kren extra scharf")
    }

    @Test("Die Fußzeile aus der Konfiguration steht unter dem Hinweis, sonst steht dort nichts")
    func aufstellungFusszeile() throws {
        var job = aufstellung
        #expect(BonRenderer.plainText(job, config: testConfig).contains("Feuerwehr") == false)

        job.footerText = "Der Reinerlös geht an die Freiwillige Feuerwehr."
        let text = BonRenderer.plainText(job, config: testConfig)
        let hinweis = try #require(text.range(of: "keine Rechnung"))
        let fussnote = try #require(text.range(of: "Freiwillige"))
        #expect(hinweis.upperBound < fussnote.lowerBound)
    }

    @Test("Passen Zeit und Kellner nicht nebeneinander, bekommt der Kellner eine eigene Zeile")
    func aufstellungAufSchmalemPapier() {
        var schmal = testConfig
        schmal.lineWidth = 32
        var job = aufstellung
        job.deviceName = "iPhone Anna"

        let zeilen = BonRenderer.plainText(job, config: schmal).components(separatedBy: "\n")
        #expect(zeilen.contains("Kellner: iPhone Anna"))
        for zeile in zeilen { #expect(zeile.count <= 32, "Zu lang: \(zeile)") }
    }

    @Test("Weder CP437 noch der ASCII-Fallback erzeugen ein Fragezeichen")
    func aufstellungOhneUndruckbareZeichen() {
        var job = aufstellung
        job.footerText = "Der Reinerlös geht an die Freiwillige Feuerwehr."

        // '?' ist das Ersatzzeichen für alles, was die Codepage nicht kennt.
        #expect([UInt8](BonRenderer.escPos(job, config: testConfig)).contains(0x3F) == false)

        var fallback = testConfig
        fallback.asciiFallback = true
        let bytes = [UInt8](BonRenderer.escPos(job, config: fallback))
        #expect(bytes.contains(0x3F) == false)
        #expect(bytes.allSatisfy { $0 < 0x80 })

        let text = String(decoding: bytes, as: UTF8.self)
        #expect(text.contains("ausschliesslich"))
        #expect(text.contains("Kaesekrainer mit Gebaeck"))
        #expect(text.contains("Reinerloes"))
        #expect(text.contains("€") == false)
    }

    private func enthaelt(_ heuhaufen: [UInt8], _ nadel: [UInt8]) -> Bool {
        guard heuhaufen.count >= nadel.count else { return false }
        for start in 0...(heuhaufen.count - nadel.count) where Array(heuhaufen[start..<start + nadel.count]) == nadel {
            return true
        }
        return false
    }
}
