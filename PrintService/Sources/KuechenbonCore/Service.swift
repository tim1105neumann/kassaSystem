import Foundation
import KassaShared

/// Ein Durchlauf des Dienstes: Delta holen, planen, drucken, Zustand sichern.
public final class Service {
    private let config: BonConfig
    private let server: KassaServerAccess
    private let printer: BonPrinter
    private let log: Logger
    /// `nil` = Probelauf: es wird nichts geschrieben.
    private let statePath: String?

    /// Katalog und Gerätenamen ändern sich fast nie; sie werden einmal geholt
    /// und nur nachgeladen, wenn im Delta etwas Unbekanntes auftaucht.
    private var catalog: [String: ArticleDTO] = [:]
    private var deviceNames: [String: String] = [:]

    /// Ein Drucker ohne Papier oder ein Server ohne Netz meldet sich bei jedem
    /// Poll — alle zwei Sekunden dieselbe Zeile würde das Log in einer Stunde
    /// um 1800 Einträge aufblähen. Deshalb wird dieselbe Meldung nur beim
    /// ersten Mal geschrieben und die Entwarnung dafür immer.
    private var letzteFehlermeldung: String?

    public private(set) var state: PrintState

    public init(
        config: BonConfig,
        state: PrintState,
        statePath: String?,
        server: KassaServerAccess,
        printer: BonPrinter,
        log: Logger
    ) {
        self.config = config
        self.state = state
        self.statePath = statePath
        self.server = server
        self.printer = printer
        self.log = log
    }

    /// - Returns: `false`, wenn der Durchlauf abgebrochen wurde. `lastSeq` ist
    ///   dann nicht weitergerückt, dasselbe Delta kommt beim nächsten Mal erneut.
    @discardableResult
    public func runOnce(now: Date = Date()) -> Bool {
        let antwort: SyncResponse
        do {
            antwort = try server.sync(since: state.lastSeq)
        } catch {
            melde(error)
            return false
        }

        // Kaltstart: `since=0` liefert die letzten drei Betriebstage auf einmal.
        // Der Planer darf davon nichts drucken, sonst spuckt der Drucker beim
        // ersten Einschalten hunderte Bons für längst serviertes Essen aus.
        let coldStart = state.lastSeq == 0

        if antwort.isFullReload {
            log.warn("Der Server hat einen Vollabgleich verlangt — der gespeicherte Stand war zu alt.")
        }

        do {
            try ladeKatalogFallsNoetig(delta: antwort.lines)
        } catch {
            melde(error)
            return false
        }
        ladeGeraeteFallsNoetig(delta: antwort.lines)

        for zeile in antwort.lines where catalog[zeile.articleId] == nil {
            log.warn(
                "Unbekannter Artikel \(zeile.articleId) (Tisch \(zeile.tableNumber), "
                + "\(zeile.nameSnapshot)) — es wurde kein Bon gedruckt."
            )
        }

        let ergebnis = BonPlanner.plan(
            delta: antwort.lines,
            catalog: catalog,
            deviceNames: deviceNames,
            state: state,
            config: config,
            now: now,
            coldStart: coldStart
        )
        for warnung in ergebnis.warnings { log.warn(warnung) }

        // Ignorierte Zeilen hängen an keinem Druck; ihre Entscheidung kann
        // sofort festgehalten werden.
        for (id, eintrag) in ergebnis.state.lines
        where eintrag.status == .ignored && state.lines[id] != eintrag {
            state.lines[id] = eintrag
        }

        if coldStart {
            log.info("Kaltstart: \(antwort.lines.count) bestehende Zeilen als erledigt vermerkt, nichts gedruckt.")
        }

        guard drucke(ergebnis, delta: antwort.lines) else {
            sichere()
            return false
        }

        entwarnung()
        state.lastSeq = antwort.maxSeq
        state = state.pruned(now: now)
        sichere()
        return true
    }

    // MARK: - Drucken

    /// Reihenfolge ist hier sicherheitsrelevant: erst drucken, dann den Zustand
    /// dieses einen Bons festhalten. Fällt der Strom zwischen Papier und
    /// Festplatte aus, wird der Bon beim nächsten Start doppelt gedruckt —
    /// ärgerlich, aber harmlos. Andersherum fehlte er für immer.
    ///
    /// Bon-Nummern beim Abbruch: der Planer hat sie bereits vergeben, aber sie
    /// gelten erst mit dem Druck. Nach jedem erfolgreichen Bon wird
    /// `nextBonNumber` auf genau `bonNumber + 1` gesetzt — nicht auf den vom
    /// Planer errechneten Endwert. Bricht der zweite von drei Bons ab, steht in
    /// der Zustandsdatei also die Nummer des abgebrochenen Bons. Beim nächsten
    /// Durchlauf kommt dasselbe Delta erneut, die bereits gedruckten Zeilen
    /// stehen als `printed` im Zustand und werden übersprungen, und der
    /// verbliebene Bon bekommt wieder dieselbe Nummer. So entsteht weder eine
    /// Lücke (die in der Küche als verlorener Bon gilt) noch eine doppelte
    /// Nummer (die zwei verschiedene Bestellungen ununterscheidbar machte).
    private func drucke(_ ergebnis: PlannerResult, delta: [OrderLineDTO]) -> Bool {
        for job in ergebnis.jobs {
            do {
                try printer.print(job, config: config)
            } catch {
                melde(error)
                return false
            }

            for id in zeilenIDs(von: job, delta: delta, geplant: ergebnis.state) {
                state.lines[id] = ergebnis.state.lines[id]
            }
            state.nextBonNumber = job.bonNumber + 1
            sichere()

            let art = job.kind == .cancellation ? "Storno-Bon" : "Bon"
            log.info("\(art) \(job.bonNumber) gedruckt (Tisch \(job.tableNumber), \(job.items.count) Positionen).")
        }
        return true
    }

    /// Welche Zeilen gehören zu diesem Bon? Der Planer liefert einen fertigen
    /// Gesamtzustand, für den bonweisen Druck braucht es die Aufteilung.
    /// Eindeutig ist sie, weil der Planer je Tisch höchstens einen Bestell- und
    /// einen Storno-Bon erzeugt.
    private func zeilenIDs(von job: BonJob, delta: [OrderLineDTO], geplant: PrintState) -> [UUID] {
        let gesucht: LineRecord.Status = job.kind == .cancellation ? .cancelled : .printed
        return delta.filter { zeile in
            guard zeile.tableNumber == job.tableNumber,
                  let neu = geplant.lines[zeile.id],
                  neu.status == gesucht else { return false }
            return state.lines[zeile.id] != neu
        }.map(\.id)
    }

    // MARK: - Katalog und Geräte

    /// Ohne diese Prüfung ginge eine neu importierte Speise still verloren: der
    /// Planer überspringt unbekannte Artikel ohne Zustandseintrag, und sobald
    /// `lastSeq` weitergerückt ist, taucht die Zeile in keinem Delta mehr auf.
    /// Der Bon wäre für immer weg. Deshalb wird der Katalog vor dem Planen neu
    /// geladen — und ein Fehler dabei bricht den Durchlauf ab, statt mit
    /// veraltetem Katalog zu planen.
    private func ladeKatalogFallsNoetig(delta: [OrderLineDTO]) throws {
        let unbekannt = delta.contains { catalog[$0.articleId] == nil }
        guard unbekannt else { return }

        let artikel = try server.articles()
        catalog = Dictionary(uniqueKeysWithValues: artikel.map { ($0.id, $0) })
    }

    /// Ein neues iPhone hat sich angemeldet. Anders als beim Katalog ist ein
    /// Fehler hier kein Abbruchgrund: fehlt der Gerätename, fehlt auf dem Bon
    /// nur die Zeile „Kellner: …“ — das Essen wird trotzdem gekocht.
    private func ladeGeraeteFallsNoetig(delta: [OrderLineDTO]) {
        let unbekannt = delta.contains { deviceNames[$0.deviceId] == nil }
        guard unbekannt else { return }

        do {
            let geraete = try server.devices()
            deviceNames = Dictionary(uniqueKeysWithValues: geraete.map { ($0.id, $0.name) })
        } catch {
            melde(error)
        }
    }

    // MARK: - Zustand und Meldungen

    private func sichere() {
        guard let statePath else { return }
        state.token = server.token
        state.deviceId = server.deviceId
        do {
            try state.save(to: statePath)
        } catch {
            melde(error, praefix: "Zustand konnte nicht gespeichert werden: ")
        }
    }

    private func melde(_ fehler: Error, praefix: String = "") {
        let text = praefix + ((fehler as? LocalizedError)?.errorDescription ?? fehler.localizedDescription)
        guard letzteFehlermeldung != text else { return }
        letzteFehlermeldung = text
        log.error(text)
    }

    private func entwarnung() {
        guard letzteFehlermeldung != nil else { return }
        letzteFehlermeldung = nil
        log.info("Wieder in Ordnung — der Dienst läuft normal weiter.")
    }
}
