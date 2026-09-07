import Foundation
import KassaShared

public enum BonRenderer {

    /// Fest im Code und nicht in der Konfiguration: dass die Aufstellung keine
    /// Rechnung ist, darf sich niemand am Küchen-Mac wegkonfigurieren können.
    /// Ohne Paragraphenzeichen — das fehlt in CP437 und käme als „?“ heraus.
    private static let hinweis = "Diese Aufstellung dient ausschließlich der "
        + "Nachvollziehbarkeit des Rechnungsbetrages und ist keine Rechnung."

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
        }

        rows.append(Row(String(repeating: "=", count: width)))
        if let bonNumber = job.bonNumber {
            rows.append(Row(spread(left: "Bon \(bonNumber)", right: timestamp(job.time), in: width)))
            if let deviceName = job.deviceName {
                rows.append(Row("Kellner: \(deviceName)"))
            }
        } else if let deviceName = job.deviceName {
            // Ohne Bonnummer ist die Zeile frei — und der Kellner muss trotzdem
            // draufstehen, damit der Zettel in der Küche zuordenbar bleibt.
            // Auf schmalem Papier bekommt er eine eigene, statt über den Rand
            // zu laufen.
            let zeit = timestamp(job.time)
            let kellner = "Kellner: \(deviceName)"
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
        }

        rows.append(Row(String(repeating: "-", count: width)))
        if job.kind == .cancellation, !job.originalBonNumbers.isEmpty {
            rows.append(Row("(war auf Bon \(job.originalBonNumbers.map(String.init).joined(separator: ", ")))"))
        }
        if job.kind == .overview {
            rows.append(contentsOf: abschluss(for: job, width: width, headWidth: headWidth))
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
