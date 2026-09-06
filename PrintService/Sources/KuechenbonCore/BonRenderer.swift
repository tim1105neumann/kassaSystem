import Foundation

public enum BonRenderer {

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
        }

        rows.append(Row(String(repeating: "=", count: width)))
        rows.append(Row(spread(left: "Bon \(job.bonNumber)", right: timestamp(job.time), in: width)))
        if let deviceName = job.deviceName {
            rows.append(Row("Kellner: \(deviceName)"))
        }
        rows.append(Row(String(repeating: "-", count: width)))

        for item in job.items {
            let prefix = String(format: "%2dx  ", item.qty)
            for (index, part) in wrapped(item.name, to: width - prefix.count).enumerated() {
                rows.append(Row(index == 0 ? prefix + part : String(repeating: " ", count: prefix.count) + part))
            }
        }

        rows.append(Row(String(repeating: "-", count: width)))
        if job.kind == .cancellation, !job.originalBonNumbers.isEmpty {
            rows.append(Row("(war auf Bon \(job.originalBonNumbers.map(String.init).joined(separator: ", ")))"))
        }
        return rows
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
