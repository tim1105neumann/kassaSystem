import Foundation
import KassaShared

public enum BonRenderer {

    /// Fest im Code und nicht in der Konfiguration: dass die Aufstellung keine
    /// Rechnung ist, darf sich niemand am Küchen-Mac wegkonfigurieren können.
    /// Ohne Paragraphenzeichen — das fehlt in CP437 und käme als „?“ heraus.
    private static let hinweis = "Diese Aufstellung dient ausschließlich der "
        + "Nachvollziehbarkeit des Rechnungsbetrages und ist keine Rechnung."

    /// Der zweite feste Hinweis, für die Tagesstatistik. Auch er gehört nicht in
    /// die Konfiguration: Was der Zettel nicht ist, darf sich am Küchen-Mac
    /// niemand wegkonfigurieren. Wieder ohne Paragraphenzeichen — das fehlt in
    /// CP437 und käme als „?“ heraus.
    private static let statistikHinweis = "Diese Übersicht ist eine interne Rechenhilfe "
        + "für den Wirt. Sie ist kein Beleg, kein Kassenabschluss im Sinne der "
        + "Registrierkassenpflicht und nicht revisionssicher."

    /// Eine Zeile des Bons. Doppelt große Zeilen haben nur die halbe
    /// Spaltenzahl zur Verfügung — das muss schon beim Zentrieren stimmen,
    /// weil der Drucker nichts nachrückt.
    private struct Row {
        var text: String
        var doubleSize: Bool

        init(_ text: String, doubleSize: Bool = false) {
            self.text = text
            self.doubleSize = doubleSize
        }
    }

    // MARK: - Öffentlich

    public static func plainText(_ job: BonJob, config: BonConfig) -> String {
        rows(for: job, config: config).map(\.text).joined(separator: "\n")
    }

    public static func escPos(_ job: BonJob, config: BonConfig) -> Data {
        var data = Data([0x1B, 0x40])       // ESC @  — Drucker zurücksetzen
        data.append(Data([0x1B, 0x74, 0x00])) // ESC t 0 — Codepage CP437
        data.append(Data([0x1B, 0x61, 0x00])) // ESC a 0 — linksbündig, zentriert wird von Hand

        for row in rows(for: job, config: config) {
            if row.doubleSize { data.append(Data([0x1D, 0x21, 0x11])) } // GS ! — doppelt hoch und breit
            data.append(CP437.encode(row.text + "\n", asciiFallback: config.asciiFallback))
            if row.doubleSize { data.append(Data([0x1D, 0x21, 0x00])) }
        }

        // Vorschub, damit die Abrisskante hinter dem Text liegt.
        data.append(CP437.encode("\n\n\n\n", asciiFallback: config.asciiFallback))
        data.append(Data([0x1D, 0x56, 0x42, 0x00])) // GS V 66 0 — Abschnitt
        return data
    }

    // MARK: - Layout

    private static func rows(for job: BonJob, config: BonConfig) -> [Row] {
        let width = config.lineWidth
        var rows: [Row] = [Row(String(repeating: "=", count: width))]

        // Der Kopf ist das Einzige, was der Koch aus zwei Metern lesen muss.
        let headWidth = width / 2
        switch job.kind {
        case .order:
            rows.append(Row(centered(spacedOut("TISCH") + "  \(job.tableNumber)", in: headWidth), doubleSize: true))
        case .cancellation:
            rows.append(Row(centered("*** " + spacedOut("STORNO") + " ***", in: headWidth), doubleSize: true))
            rows.append(Row(centered("TISCH \(job.tableNumber)", in: headWidth), doubleSize: true))
        case .overview:
            // Bewusst nicht im „TISCH n“-Format der Küchenbons: der Zettel liegt
            // in der Küche zwischen echten Bons, und der Koch darf ihn nicht für
            // eine Bestellung halten, die noch zu kochen wäre.
            rows.append(Row(centered(spacedOut("AUFSTELLUNG"), in: headWidth), doubleSize: true))
            rows.append(Row(centered("Tisch \(job.tableNumber)", in: headWidth), doubleSize: true))
        case .dayReport:
            // Nicht gesperrt wie die anderen Köpfe: „T A G E S S T A T I S T I K“
            // wären 27 Zeichen, doppelt groß stehen aber nur 24 zur Verfügung.
            // Der Drucker rückt nichts nach, er bricht um — und der Kopf stünde
            // zweizeilig da. Also bitte nicht „reparieren“.
            rows.append(Row(centered("TAGESSTATISTIK", in: headWidth), doubleSize: true))
            // Statt der Tischzeile der Betriebstag: so trägt der Zettel an keiner
            // Stelle das Wort „Tisch“ und der Koch kann ihn mit nichts
            // verwechseln. Das Datum muss ohnehin drauf, weil alte Tage
            // nachdruckbar sind.
            rows.append(Row(centered(betriebstag(job.statistics?.businessDay ?? ""), in: headWidth), doubleSize: true))
        }

        rows.append(Row(String(repeating: "=", count: width)))
        // Den Kellner gibt es bei einer Tagesstatistik nicht — sie gehört dem
        // Wirt, und das Gerät sagt ihm, von welchem Schirm aus er sie angefordert
        // hat. Der einzige Unterschied im gemeinsamen Kopfblock.
        let geraetLabel = job.kind == .dayReport ? "Gerät" : "Kellner"
        if let bonNumber = job.bonNumber {
            rows.append(Row(spread(left: "Bon \(bonNumber)", right: timestamp(job.time), in: width)))
            if let deviceName = job.deviceName {
                rows.append(Row("\(geraetLabel): \(deviceName)"))
            }
        } else if let deviceName = job.deviceName {
            // Ohne Bonnummer ist die Zeile frei — und der Kellner muss trotzdem
            // draufstehen, damit der Zettel in der Küche zuordenbar bleibt.
            // Auf schmalem Papier bekommt er eine eigene, statt über den Rand
            // zu laufen.
            let zeit = timestamp(job.time)
            let kellner = "\(geraetLabel): \(deviceName)"
            if zeit.count + kellner.count < width {
                rows.append(Row(spread(left: zeit, right: kellner, in: width)))
            } else {
                rows.append(Row(zeit))
                rows.append(Row(kellner))
            }
        } else {
            rows.append(Row(timestamp(job.time)))
        }
        rows.append(Row(String(repeating: "-", count: width)))

        // Die Kennzahlen stehen dort, wo sonst die Positionen stehen — ein
        // Statistik-Job hat keine, die Schleife darunter läuft für ihn leer.
        if let statistik = job.statistics {
            rows.append(contentsOf: kennzahlen(statistik, width: width))
        }

        for item in job.items {
            let prefix = String(format: "%2dx  ", item.qty)
            let betrag = item.unitPriceCents.map { Money(cents: $0 * item.qty).formattedPlain }
            let teile = wrapped(item.name, to: width - prefix.count - (betrag.map { $0.count + 2 } ?? 0))
            for (index, part) in teile.enumerated() {
                let links = index == 0 ? prefix + part : String(repeating: " ", count: prefix.count) + part
                // Der Betrag gehört auf die letzte Zeile der Position: bei einem
                // umgebrochenen Namen sähe er sonst aus, als zählte er nur für
                // die erste Hälfte.
                if let betrag, index == teile.count - 1 {
                    rows.append(Row(spread(left: links, right: betrag, in: width)))
                } else {
                    rows.append(Row(links))
                }
            }

            // Erst nach der Namens-Schleife, damit der rechtsbündige Betrag auf
            // der letzten *Namens*zeile bleibt. Auf der Aufstellung bleibt der
            // Sonderwunsch weg: die ist ein Preiszettel zum Nachrechnen für den
            // Gast, Küchenanweisungen machen sie nur unruhig. Die Sperre steht
            // hier und nicht nur im Planer, weil sie fachlich ist und nicht an
            // einer vergessenen Zeile in `planOverviews` hängen darf.
            if let note = item.note, job.kind != .overview {
                let einzug = String(repeating: " ", count: prefix.count)
                // ASCII: „»" gäbe es in CP437, käme aber im Fallback als „?".
                let marker = ">> "
                // Vier Zeichen Reserve, weil `centered`, `spread` und `wrapped`
                // in Zeichen rechnen, der Drucker aber Bytes zählt: mit
                // `asciiFallback` wird aus „ü" ein „ue", und eine randvolle
                // Zeile bräche er selbst noch einmal um. Freitext vom Kellner
                // ist umlautreicher als die kurzen Namen aus der Preisliste.
                let notizBreite = width - einzug.count - marker.count - 4
                for (index, teil) in wrapped(note, to: notizBreite).enumerated() {
                    let fortsetzung = String(repeating: " ", count: marker.count)
                    rows.append(Row(einzug + (index == 0 ? marker : fortsetzung) + teil))
                }
            }
        }

        rows.append(Row(String(repeating: "-", count: width)))
        if job.kind == .cancellation, !job.originalBonNumbers.isEmpty {
            rows.append(Row("(war auf Bon \(job.originalBonNumbers.map(String.init).joined(separator: ", ")))"))
        }
        if job.kind == .overview {
            rows.append(contentsOf: abschluss(for: job, width: width, headWidth: headWidth))
        }
        if let statistik = job.statistics {
            rows.append(contentsOf: statistikbloecke(statistik, width: width, headWidth: headWidth))
        }
        return rows
    }

    /// Summe und Hinweistext. `EUR` statt `€`: das Eurozeichen fehlt in CP437
    /// und käme als „?“ aus dem Drucker (siehe CP437.swift).
    private static func abschluss(for job: BonJob, width: Int, headWidth: Int) -> [Row] {
        let summe = Money(cents: job.totalCents ?? 0).formattedPlain
        var rows: [Row] = [
            Row(""),
            Row(spread(left: "SUMME", right: "\(summe) EUR", in: headWidth), doubleSize: true),
            Row(""),
            Row(String(repeating: "=", count: width))
        ]
        rows.append(contentsOf: eingerueckt(hinweis, width: width))
        if let footerText = job.footerText, !footerText.isEmpty {
            rows.append(Row(""))
            rows.append(contentsOf: eingerueckt(footerText, width: width))
        }
        rows.append(Row(String(repeating: "=", count: width)))
        return rows
    }

    /// Die drei Zahlen, die der Wirt zuerst sucht. Sie bleiben auch an einem Tag
    /// ohne Umsatz stehen: eine fehlende Zeile ließe offen, ob nichts kassiert
    /// wurde oder ob der Zettel unvollständig ist.
    private static func kennzahlen(_ statistik: BonJob.Statistics, width: Int) -> [Row] {
        [
            Row(spread(left: "Umsatz", right: "\(Money(cents: statistik.totalCents).formattedPlain) EUR", in: width)),
            Row(spread(left: "Trinkgeld", right: "\(Money(cents: statistik.tipCents).formattedPlain) EUR", in: width)),
            // Ohne EUR, weil es Stück sind — die Spalte bleibt trotzdem bündig.
            Row(spread(left: "Kassiervorgänge", right: "\(statistik.settlementCount)", in: width))
        ]
    }

    /// Kassa-Zeile, die beiden Aufschlüsselungen und der Hinweis. Leere
    /// Abschnitte fallen samt Überschrift und Trennlinie weg: ein Tag ohne
    /// Umsatz soll kein Gerüst aus leeren Strichen ergeben.
    private static func statistikbloecke(_ statistik: BonJob.Statistics, width: Int, headWidth: Int) -> [Row] {
        // `KASSA` und nicht `KASSA GESAMT`: das wären mit „12345,67 EUR“ schon
        // 24 Zeichen ohne Lücke dazwischen, und die doppelt große Zeile bräche
        // dem Wirt mitten in der Summe um. Die beiden Zeilen darüber sagen
        // ohnehin unmissverständlich, was hier summiert wurde.
        let kassa = Money(cents: statistik.totalCents + statistik.tipCents).formattedPlain
        var rows: [Row] = [Row(spread(left: "KASSA", right: "\(kassa) EUR", in: headWidth), doubleSize: true)]

        if !statistik.byCategory.isEmpty {
            rows.append(Row(String(repeating: "-", count: width)))
            rows.append(Row("NACH KATEGORIE"))
            for eintrag in statistik.byCategory { rows.append(contentsOf: zeilen(for: eintrag, width: width)) }
        }
        if !statistik.topArticles.isEmpty {
            rows.append(Row(String(repeating: "-", count: width)))
            rows.append(Row("MEISTVERKAUFT"))
            for eintrag in statistik.topArticles { rows.append(contentsOf: zeilen(for: eintrag, width: width)) }
        }

        rows.append(Row(String(repeating: "=", count: width)))
        rows.append(contentsOf: eingerueckt(statistikHinweis, width: width))
        rows.append(Row(String(repeating: "=", count: width)))
        return rows
    }

    /// Eine Zeile der Aufschlüsselung. Ohne Menge — bei den Kategorien — beginnt
    /// der Name dort, wo bei den Artikeln die Mengenspalte steht, damit beide
    /// Blöcke dieselbe Kante haben.
    private static func zeilen(for eintrag: BonJob.Statistics.Entry, width: Int) -> [Row] {
        let prefix = eintrag.qty.map { String(format: "%4dx ", $0) } ?? "  "
        let betrag = "\(Money(cents: eintrag.cents).formattedPlain) EUR"
        let teile = wrapped(eintrag.label, to: width - prefix.count - betrag.count - 2)
        return teile.enumerated().map { index, teil in
            let links = index == 0 ? prefix + teil : String(repeating: " ", count: prefix.count) + teil
            // Betrag auf die letzte Zeile, aus demselben Grund wie bei den Positionen.
            return index == teile.count - 1 ? Row(spread(left: links, right: betrag, in: width)) : Row(links)
        }
    }

    /// `2025-09-06` wird zu `Sa 06.09.2025`. Lässt sich der Tag nicht lesen,
    /// wird er roh gedruckt: ein Zettel mit seltsamem Datum ist immer noch
    /// brauchbar, einer ohne Datum nicht — und alte Tage sind nachdruckbar.
    private static func betriebstag(_ tag: String) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "de_AT")
        formatter.timeZone = TimeZone(identifier: "Europe/Vienna")
        formatter.dateFormat = "yyyy-MM-dd"
        guard let datum = formatter.date(from: tag) else { return tag }

        // Wochentagspunkt weg wie in `timestamp` — er kostet auf dem Bon nur Platz.
        formatter.dateFormat = "EE"
        let wochentag = formatter.string(from: datum).replacingOccurrences(of: ".", with: "")
        formatter.dateFormat = "dd.MM.yyyy"
        return "\(wochentag) \(formatter.string(from: datum))"
    }

    private static func eingerueckt(_ text: String, width: Int) -> [Row] {
        wrapped(text, to: width - 2).map { Row("  " + $0) }
    }

    /// „TISCH" wird zu „T I S C H" — gesperrt liest sich der Kopf auf dem
    /// schmalen Bon deutlich ruhiger.
    private static func spacedOut(_ text: String) -> String {
        text.map(String.init).joined(separator: " ")
    }

    private static func centered(_ text: String, in width: Int) -> String {
        let padding = max(0, (width - text.count) / 2)
        return String(repeating: " ", count: padding) + text
    }

    private static func spread(left: String, right: String, in width: Int) -> String {
        let gap = max(1, width - left.count - right.count)
        return left + String(repeating: " ", count: gap) + right
    }

    /// Lange Artikelnamen werden umgebrochen, nie abgeschnitten: „Käsekrainer
    /// mit Gebäck" darf in der Küche nicht als „Käsekrainer mit" ankommen.
    private static func wrapped(_ text: String, to width: Int) -> [String] {
        guard width > 0 else { return [text] }
        var lines: [String] = []
        var current = ""

        for word in text.split(separator: " ", omittingEmptySubsequences: true).map(String.init) {
            var word = word
            // Ein einzelnes Wort, das nie in eine Zeile passt, wird hart getrennt.
            while word.count > width {
                if !current.isEmpty {
                    lines.append(current)
                    current = ""
                }
                lines.append(String(word.prefix(width)))
                word = String(word.dropFirst(width))
            }
            if current.isEmpty {
                current = word
            } else if current.count + 1 + word.count <= width {
                current += " " + word
            } else {
                lines.append(current)
                current = word
            }
        }
        if !current.isEmpty { lines.append(current) }
        return lines.isEmpty ? [""] : lines
    }

    /// `Sa 06.09.  19:42`. ICU hängt an das Wochentagskürzel einen Punkt,
    /// der auf dem Bon nur Platz kostet.
    private static func timestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "de_AT")
        formatter.timeZone = TimeZone(identifier: "Europe/Vienna")

        formatter.dateFormat = "EE"
        let weekday = formatter.string(from: date).replacingOccurrences(of: ".", with: "")
        formatter.dateFormat = "dd.MM."
        let day = formatter.string(from: date)
        formatter.dateFormat = "HH:mm"
        return "\(weekday) \(day)  \(formatter.string(from: date))"
    }
}
