import Foundation
import KassaShared
import Testing
@testable import KuechenbonCore

@Suite("Bonplanung")
struct BonPlannerTests {

    @Test("Zwei Speisen und ein Getränk auf einem Tisch ergeben einen Bon mit genau den zwei Speisen")
    func nurSpeisenAufDenBon() {
        let jetzt = wienerZeit(6, 19, 42)
        let ergebnis = planen([
            zeile(Katalog.krainer, tisch: 5, qty: 2, createdAt: jetzt),
            zeile(Katalog.bratwurst, tisch: 5, createdAt: jetzt.addingTimeInterval(1)),
            zeile(Katalog.bier, tisch: 5, qty: 3, createdAt: jetzt.addingTimeInterval(2))
        ], now: jetzt)

        #expect(ergebnis.jobs.count == 1)
        let bon = ergebnis.jobs[0]
        #expect(bon.kind == .order)
        #expect(bon.tableNumber == 5)
        #expect(bon.bonNumber == 1)
        #expect(bon.deviceName == "iPhone Anna")
        #expect(bon.items == [
            BonJob.Item(qty: 2, name: "Käsekrainer mit Gebäck"),
            BonJob.Item(qty: 1, name: "Bratwurst mit Pommes")
        ])
    }

    @Test("Eine Nachbestellung fünf Minuten später ergibt einen zweiten Bon mit nur der neuen Position")
    func nachbestellungDrucktNurDasNeue() {
        let jetzt = wienerZeit(6, 19, 42)
        let erst = planen([
            zeile(Katalog.krainer, tisch: 5, qty: 2, createdAt: jetzt)
        ], now: jetzt)

        let spaeter = jetzt.addingTimeInterval(5 * 60)
        let zweit = planen([
            zeile(Katalog.bratwurst, tisch: 5, createdAt: spaeter)
        ], state: erst.state, now: spaeter)

        #expect(zweit.jobs.count == 1)
        #expect(zweit.jobs[0].bonNumber == 2)
        #expect(zweit.jobs[0].items == [BonJob.Item(qty: 1, name: "Bratwurst mit Pommes")])
    }

    @Test("Eine beim ersten Sehen schon kassierte Zeile wird nie gedruckt")
    func kassierteZeileNiemalsDrucken() {
        let jetzt = wienerZeit(6, 19, 42)
        let id = UUID()
        let erst = planen([
            zeile(Katalog.krainer, tisch: 5, id: id, createdAt: jetzt, settlementId: UUID())
        ], now: jetzt)

        #expect(erst.jobs.isEmpty)
        #expect(erst.state.lines[id]?.status == .ignored)

        // Auch wenn dieselbe Zeile in einem späteren Delta noch einmal auftaucht.
        let zweit = planen([
            zeile(Katalog.krainer, tisch: 5, id: id, createdAt: jetzt, settlementId: UUID(), seq: 2)
        ], state: erst.state, now: jetzt.addingTimeInterval(60))
        #expect(zweit.jobs.isEmpty)
    }

    @Test("Eine beim ersten Sehen bereits stornierte Zeile wird nicht gedruckt")
    func vorabStornierteZeileNichtDrucken() {
        let jetzt = wienerZeit(6, 19, 42)
        let id = UUID()
        let ergebnis = planen([
            zeile(Katalog.krainer, tisch: 5, id: id, createdAt: jetzt, voidedAt: jetzt)
        ], now: jetzt)

        #expect(ergebnis.jobs.isEmpty)
        #expect(ergebnis.state.lines[id]?.status == .ignored)
    }

    @Test("Ein Storno nach dem Druck ergibt genau einen Storno-Bon, auch bei wiederholtem Delta")
    func stornoNachDruckGenauEinmal() {
        let jetzt = wienerZeit(6, 19, 42)
        let id = UUID()
        let erst = planen([
            zeile(Katalog.krainer, tisch: 5, qty: 2, id: id, createdAt: jetzt)
        ], now: jetzt)
        #expect(erst.jobs.count == 1)

        let stornoZeit = jetzt.addingTimeInterval(120)
        let zweit = planen([
            zeile(Katalog.krainer, tisch: 5, qty: 2, id: id, createdAt: jetzt, voidedAt: stornoZeit, seq: 2)
        ], state: erst.state, now: stornoZeit)

        #expect(zweit.jobs.count == 1)
        #expect(zweit.jobs[0].kind == .cancellation)
        #expect(zweit.jobs[0].tableNumber == 5)
        #expect(zweit.jobs[0].bonNumber == 2)
        #expect(zweit.jobs[0].originalBonNumbers == [1])
        #expect(zweit.state.lines[id]?.status == .cancelled)

        let dritt = planen([
            zeile(Katalog.krainer, tisch: 5, qty: 2, id: id, createdAt: jetzt, voidedAt: stornoZeit, seq: 3)
        ], state: zweit.state, now: stornoZeit.addingTimeInterval(60))
        #expect(dritt.jobs.isEmpty)
    }

    @Test("Eine Mengenreduktion ergibt einen Storno-Bon und einen neuen Bestell-Bon")
    func mengenreduktion() {
        let jetzt = wienerZeit(6, 19, 42)
        let alt = UUID()
        let erst = planen([
            zeile(Katalog.krainer, tisch: 5, qty: 3, id: alt, createdAt: jetzt)
        ], now: jetzt)

        // Die App storniert die alte Zeile und bucht eine neue mit der Restmenge.
        let spaeter = jetzt.addingTimeInterval(90)
        let zweit = planen([
            zeile(Katalog.krainer, tisch: 5, qty: 3, id: alt, createdAt: jetzt, voidedAt: spaeter, seq: 2),
            zeile(Katalog.krainer, tisch: 5, qty: 1, createdAt: spaeter, seq: 3)
        ], state: erst.state, now: spaeter)

        #expect(zweit.jobs.count == 2)
        #expect(zweit.jobs[0].kind == .cancellation)
        #expect(zweit.jobs[0].bonNumber == 2)
        #expect(zweit.jobs[0].items == [BonJob.Item(qty: 3, name: "Käsekrainer mit Gebäck")])
        #expect(zweit.jobs[0].originalBonNumbers == [1])
        #expect(zweit.jobs[1].kind == .order)
        #expect(zweit.jobs[1].bonNumber == 3)
        #expect(zweit.jobs[1].items == [BonJob.Item(qty: 1, name: "Käsekrainer mit Gebäck")])
    }

    @Test("Der Storno-Bon trägt die Uhrzeit der Stornierung, nicht die der Bestellung")
    func stornoZeigtStornozeit() {
        let bestellt = wienerZeit(6, 19, 42)
        let storniert = wienerZeit(6, 20, 15)
        let id = UUID()
        let erst = planen([
            zeile(Katalog.krainer, tisch: 5, id: id, createdAt: bestellt)
        ], now: bestellt)

        let zweit = planen([
            zeile(Katalog.krainer, tisch: 5, id: id, createdAt: bestellt, voidedAt: storniert, seq: 2)
        ], state: erst.state, now: storniert)

        #expect(zweit.jobs.count == 1)
        #expect(zweit.jobs[0].kind == .cancellation)
        #expect(zweit.jobs[0].time == storniert)
    }

    @Test("Ein Kaltstart druckt gar nichts, vermerkt aber alle Zeilen als erledigt")
    func kaltstartDrucktNichts() {
        let jetzt = wienerZeit(6, 19, 42)
        let a = UUID()
        let b = UUID()
        let ergebnis = planen([
            zeile(Katalog.krainer, tisch: 5, id: a, createdAt: jetzt),
            zeile(Katalog.bratwurst, tisch: 7, id: b, createdAt: jetzt)
        ], now: jetzt, coldStart: true)

        #expect(ergebnis.jobs.isEmpty)
        #expect(ergebnis.state.lines[a]?.status == .ignored)
        #expect(ergebnis.state.lines[b]?.status == .ignored)
        #expect(ergebnis.state.nextBonNumber == 1)
    }

    @Test("Eine Zeile älter als maxBonAgeMinutes wird nicht gedruckt, erzeugt aber eine Warnung")
    func nachzuegleUeberspringen() {
        let jetzt = wienerZeit(6, 19, 42)
        let id = UUID()
        let ergebnis = planen([
            zeile(Katalog.krainer, tisch: 5, id: id, createdAt: jetzt.addingTimeInterval(-121 * 60))
        ], now: jetzt)

        #expect(ergebnis.jobs.isEmpty)
        #expect(ergebnis.state.lines[id]?.status == .ignored)
        #expect(ergebnis.warnings.count == 1)
        #expect(ergebnis.warnings[0].contains("Käsekrainer mit Gebäck"))

        // Knapp innerhalb der Grenze wird sehr wohl gedruckt.
        let frisch = planen([
            zeile(Katalog.krainer, tisch: 5, createdAt: jetzt.addingTimeInterval(-119 * 60))
        ], now: jetzt)
        #expect(frisch.jobs.count == 1)
        #expect(frisch.warnings.isEmpty)
    }

    @Test("Kaffee und Mehlspeise erscheinen nie, obwohl sie in der Kategorie Speisen liegen")
    func ausgenommeneArtikel() {
        let jetzt = wienerZeit(6, 19, 42)
        let kaffeeId = UUID()
        let ergebnis = planen([
            zeile(Katalog.kaffee, tisch: 5, qty: 2, id: kaffeeId, createdAt: jetzt),
            zeile(Katalog.mehlspeise, tisch: 5, createdAt: jetzt),
            zeile(Katalog.krainer, tisch: 5, createdAt: jetzt.addingTimeInterval(1))
        ], now: jetzt)

        #expect(ergebnis.jobs.count == 1)
        #expect(ergebnis.jobs[0].items == [BonJob.Item(qty: 1, name: "Käsekrainer mit Gebäck")])
        #expect(ergebnis.state.lines[kaffeeId] == nil)
    }

    @Test("Getränke und unbekannte Artikel legen keinen Zustandseintrag an")
    func zustandWaechstNichtDurchGetraenke() {
        let jetzt = wienerZeit(6, 19, 42)
        let ergebnis = planen([
            zeile(Katalog.bier, tisch: 5, qty: 4, createdAt: jetzt),
            zeile(Katalog.bier, tisch: 7, createdAt: jetzt),
            OrderLineDTO(id: UUID(), tableNumber: 5, articleId: "gibts-nicht", nameSnapshot: "Unbekannt",
                         unitPriceCents: 100, qty: 1, createdAt: jetzt, deviceId: "d-anna", updatedSeq: 1)
        ], now: jetzt)

        #expect(ergebnis.jobs.isEmpty)
        #expect(ergebnis.state.lines.isEmpty)
    }

    @Test("Bon-Nummern laufen lückenlos und aufsteigend, auch gemischt mit Storno-Bons")
    func bonNummernLueckenlos() {
        let jetzt = wienerZeit(6, 19, 42)
        let alt = UUID()
        var state = PrintState()

        let erst = planen([
            zeile(Katalog.krainer, tisch: 3, id: alt, createdAt: jetzt),
            zeile(Katalog.bratwurst, tisch: 9, createdAt: jetzt)
        ], state: state, now: jetzt)
        state = erst.state
        #expect(erst.jobs.map(\.bonNumber) == [1, 2])

        let spaeter = jetzt.addingTimeInterval(60)
        let zweit = planen([
            zeile(Katalog.krainer, tisch: 3, id: alt, createdAt: jetzt, voidedAt: spaeter, seq: 2),
            zeile(Katalog.bratwurst, tisch: 3, createdAt: spaeter, seq: 3),
            zeile(Katalog.krainer, tisch: 9, createdAt: spaeter, seq: 4)
        ], state: state, now: spaeter)

        #expect(zweit.jobs.map(\.bonNumber) == [3, 4, 5])
        #expect(zweit.jobs.map(\.kind) == [.cancellation, .order, .order])
        #expect(zweit.state.nextBonNumber == 6)
    }

    @Test("Zwei Tische im selben Delta ergeben zwei getrennte Bons, nach Tischnummer sortiert")
    func zweiTischeZweiBons() {
        let jetzt = wienerZeit(6, 19, 42)
        let ergebnis = planen([
            zeile(Katalog.krainer, tisch: 12, createdAt: jetzt, deviceId: "d-bert"),
            zeile(Katalog.bratwurst, tisch: 4, createdAt: jetzt)
        ], now: jetzt)

        #expect(ergebnis.jobs.count == 2)
        #expect(ergebnis.jobs.map(\.tableNumber) == [4, 12])
        #expect(ergebnis.jobs[0].deviceName == "iPhone Anna")
        #expect(ergebnis.jobs[1].deviceName == "iPad Bert")
    }

    @Test("Ein unbekanntes Gerät lässt den Kellnernamen weg")
    func unbekanntesGeraet() {
        let jetzt = wienerZeit(6, 19, 42)
        let ergebnis = planen([
            zeile(Katalog.krainer, tisch: 5, createdAt: jetzt, deviceId: "d-fremd")
        ], now: jetzt)

        #expect(ergebnis.jobs[0].deviceName == nil)
    }

    @Test("Gleiche Artikel bleiben getrennte Positionen in Buchungsreihenfolge")
    func gleicheArtikelNichtZusammenfassen() {
        let jetzt = wienerZeit(6, 19, 42)
        let ergebnis = planen([
            zeile(Katalog.krainer, tisch: 5, qty: 1, createdAt: jetzt.addingTimeInterval(10)),
            zeile(Katalog.krainer, tisch: 5, qty: 2, createdAt: jetzt)
        ], now: jetzt.addingTimeInterval(20))

        #expect(ergebnis.jobs[0].items == [
            BonJob.Item(qty: 2, name: "Käsekrainer mit Gebäck"),
            BonJob.Item(qty: 1, name: "Käsekrainer mit Gebäck")
        ])
        #expect(ergebnis.jobs[0].time == jetzt)
    }
}
