import Crypto
import Fluent
import Foundation
import KassaShared

enum PriceList {
    struct Row: Equatable {
        var category: String
        var name: String
        var priceCents: Int
    }

    struct ParseError: Error, CustomStringConvertible {
        let description: String
    }

    /// Deterministisch: eine reine Preisänderung behält die ID, damit alte
    /// Bestellzeilen weiter auf ihren Artikel zeigen.
    static func articleID(category: String, name: String) -> String {
        let digest = SHA256.hash(data: Data("\(category)|\(name)".utf8))
        return String(digest.map { String(format: "%02x", $0) }.joined().prefix(16))
    }

    // MARK: - CSV

    /// Minimaler RFC-4180-Parser: Felder in Anführungszeichen, Kommas und
    /// verdoppelte Anführungszeichen darin, CRLF wie LF.
    ///
    /// Läuft über Unicode-Skalare statt über Characters, weil Swift "\r\n" zu
    /// einem einzigen Character zusammenfasst — als Character verglichen wäre
    /// eine CRLF-Datei eine einzige endlose Zeile.
    static func records(in text: String) -> [[String]] {
        var records: [[String]] = []
        var record: [String] = []
        var field = ""
        var quoted = false
        let scalars = Array(text.unicodeScalars)
        var index = 0

        while index < scalars.count {
            let scalar = scalars[index]
            if quoted {
                if scalar == "\"" {
                    if index + 1 < scalars.count, scalars[index + 1] == "\"" {
                        field.unicodeScalars.append("\"")
                        index += 2
                    } else {
                        quoted = false
                        index += 1
                    }
                } else {
                    field.unicodeScalars.append(scalar)
                    index += 1
                }
                continue
            }

            switch scalar {
            case "\"":
                quoted = true
            case ",":
                record.append(field)
                field = ""
            case "\r":
                break
            case "\n":
                record.append(field)
                records.append(record)
                record = []
                field = ""
            default:
                field.unicodeScalars.append(scalar)
            }
            index += 1
        }

        if !field.isEmpty || !record.isEmpty {
            record.append(field)
            records.append(record)
        }

        return records.filter { row in
            !row.allSatisfy { $0.trimmingCharacters(in: .whitespaces).isEmpty }
        }
    }

    static func parse(_ text: String) throws -> [Row] {
        var rows = records(in: text)
        guard !rows.isEmpty else { throw ParseError(description: "Preisliste ist leer") }
        rows.removeFirst() // Kopfzeile

        return try rows.map { record in
            guard record.count >= 3 else {
                throw ParseError(description: "Zeile mit \(record.count) statt 3 Feldern: \(record)")
            }
            let category = record[0].trimmingCharacters(in: .whitespaces)
            let name = record[1].trimmingCharacters(in: .whitespaces)
            let rawPrice = record[2].trimmingCharacters(in: .whitespaces)
            // Money parst über Ganzzahlen — "9.20" wird exakt 920, nicht 919.
            guard let money = Money(parsing: rawPrice) else {
                throw ParseError(description: "Unlesbarer Preis '\(rawPrice)' bei '\(name)'")
            }
            guard !category.isEmpty, !name.isEmpty else {
                throw ParseError(description: "Kategorie oder Artikelname fehlt: \(record)")
            }
            return Row(category: category, name: name, priceCents: money.cents)
        }
    }

    // MARK: - Import

    /// Legt neue Artikel an, aktualisiert bestehende und deaktiviert alles, was
    /// nicht mehr in der CSV steht. Gelöscht wird nie — sonst brechen alte Zeilen.
    @discardableResult
    static func `import`(rows: [Row], on db: any Database) async throws -> Int {
        let existing = try await Article.query(on: db).all()
        var byID = Dictionary(uniqueKeysWithValues: try existing.map { (try $0.requireID(), $0) })
        var seen: Set<String> = []

        for (offset, row) in rows.enumerated() {
            let id = articleID(category: row.category, name: row.name)
            seen.insert(id)
            if let article = byID[id] {
                article.category = row.category
                article.name = row.name
                article.priceCents = row.priceCents
                article.sortOrder = offset
                article.active = true
                try await article.save(on: db)
            } else {
                let article = Article(
                    id: id,
                    category: row.category,
                    name: row.name,
                    priceCents: row.priceCents,
                    sortOrder: offset
                )
                try await article.create(on: db)
                byID[id] = article
            }
        }

        for (id, article) in byID where !seen.contains(id) && article.active {
            article.active = false
            try await article.save(on: db)
        }

        let state = try await ServerState.current(on: db)
        state.catalogVersion += 1
        try await state.save(on: db)

        return rows.count
    }

    @discardableResult
    static func importFile(at path: String, on db: any Database, lock: WriteLock) async throws -> Int {
        let url = URL(fileURLWithPath: path)
        let text = try String(contentsOf: url, encoding: .utf8)
        let rows = try parse(text)
        return try await lock.run {
            try await db.transaction { tx in try await self.import(rows: rows, on: tx) }
        }
    }
}
