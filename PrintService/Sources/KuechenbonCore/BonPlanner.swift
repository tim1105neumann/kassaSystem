import Foundation
import KassaShared

/// Ein druckfertiger Bon. Enthält nichts mehr, was noch nachgeschlagen
/// werden müsste — der Renderer kommt ohne Katalog aus.
public struct BonJob: Equatable, Sendable {
    public enum Kind: String, Sendable { case order, cancellation, overview }

    public struct Item: Equatable, Sendable {
        public var qty: Int
        public var name: String
        /// Nur auf der Aufstellung befüllt. Auf dem Küchenbon steht bewusst kein
        /// Preis: der Koch braucht ihn nicht, und jede Zahl mehr ist eine Zahl,
        /// die er beim Überfliegen aussortieren muss.
        public var unitPriceCents: Int?

        public init(qty: Int, name: String, unitPriceCents: Int? = nil) {
            self.qty = qty
            self.name = name
            self.unitPriceCents = unitPriceCents
        }
    }

    public var kind: Kind
    public var tableNumber: Int
    /// `nil` auf der Aufstellung: die fortlaufende Nummer ist das Mittel, mit
    /// dem die Küche einen fehlenden Bon bemerkt. Zählten Aufstellungen mit,
    /// entstünden in der Bonfolge Lücken, hinter denen nichts steckt — und der
    /// Mechanismus wäre entwertet.
    public var bonNumber: Int?
    public var time: Date
    public var deviceName: String?
    public var items: [Item]
    /// Nur bei `.cancellation` befüllt: die Bons, auf denen das Storno stand.
    public var originalBonNumbers: [Int]
    /// Nur bei `.overview` befüllt.
    public var totalCents: Int?
    public var footerText: String?

    public init(
        kind: Kind,
        tableNumber: Int,
        bonNumber: Int?,
        time: Date,
        deviceName: String?,
        items: [Item],
        originalBonNumbers: [Int] = [],
        totalCents: Int? = nil,
        footerText: String? = nil
    ) {
        self.kind = kind
        self.tableNumber = tableNumber
        self.bonNumber = bonNumber
        self.time = time
        self.deviceName = deviceName
        self.items = items
        self.originalBonNumbers = originalBonNumbers
        self.totalCents = totalCents
        self.footerText = footerText
    }
}

public struct PlannerResult: Equatable {
    public var jobs: [BonJob]
    public var state: PrintState
    /// Nur fürs Log — nichts davon ist ein Abbruchgrund.
    public var warnings: [String]

    public init(jobs: [BonJob], state: PrintState, warnings: [String]) {
        self.jobs = jobs
        self.state = state
        self.warnings = warnings
    }
}

public enum BonPlanner {

    /// Rein: gleiche Eingabe, gleiche Bons. Kein I/O, keine Systemuhr —
    /// nur so lässt sich jede der Sonderregeln unten überhaupt testen.
    public static func plan(
        delta: [OrderLineDTO],
        catalog: [String: ArticleDTO],
        deviceNames: [String: String],
        state: PrintState,
        config: BonConfig,
        now: Date,
        coldStart: Bool
    ) -> PlannerResult {
        var state = state
        var warnings: [String] = []
        var toPrint: [OrderLineDTO] = []
        var toCancel: [(line: OrderLineDTO, originalBon: Int?)] = []

        let maxAge = TimeInterval(config.maxBonAgeMinutes * 60)

        for line in delta {
            // Getränke und ausgenommene Artikel bekommen bewusst KEINEN
            // Zustandseintrag: sonst wüchse die Zustandsdatei mit jedem
            // Spritzer, obwohl es dazu nie eine Entscheidung zu merken gibt.
            guard let article = catalog[line.articleId],
                  article.category == config.foodCategory,
                  !config.excludedArticles.contains(article.name) else { continue }

            if let record = state.lines[line.id] {
                switch record.status {
                case .printed:
                    // Reduziert jemand am Tisch die Menge, storniert die App die
                    // alte Zeile und bucht eine neue (App/Sources/Sync/LocalStore.swift:148-171).
                    // Die Küche muss erfahren, dass das schon Gekochte wegfällt.
                    if line.voidedAt != nil {
                        toCancel.append((line, record.bonNumber))
                    }
                case .ignored, .cancelled:
                    // Entscheidung ist gefallen, ein zweites Delta ändert daran nichts.
                    break
                }
                continue
            }

            if coldStart {
                // Beim allerersten Lauf liefert `since=0` die letzten drei
                // Betriebstage. Ohne diese Regel spuckt der Drucker beim
                // Einschalten hunderte Bons für längst serviertes Essen aus.
                state.lines[line.id] = LineRecord(status: .ignored, at: now)
            } else if line.settlementId != nil {
                // Bei einer Teilzahlung splittet der Server die Zeile und legt
                // für den kassierten Anteil eine neue Zeile mit neuer UUID an
                // (Server/Sources/KassaServer/SettlementRoute.swift:119). Die sieht
                // aus wie eine Nachbestellung, ist aber längst serviertes Essen.
                // Eine echte Bestellung kommt nie schon kassiert zur Welt.
                state.lines[line.id] = LineRecord(status: .ignored, at: now)
            } else if line.voidedAt != nil {
                // Storniert, bevor wir sie gesehen haben — gekocht wurde nie etwas.
                state.lines[line.id] = LineRecord(status: .ignored, at: now)
            } else if now.timeIntervalSince(line.createdAt) > maxAge {
                // Nachzügler nach einem Netzausfall: das Essen ist längst am Tisch.
                state.lines[line.id] = LineRecord(status: .ignored, at: now)
                warnings.append(
                    "Zeile \(line.id) (Tisch \(line.tableNumber), \(line.nameSnapshot)) ist älter als "
                    + "\(config.maxBonAgeMinutes) Minuten und wurde nicht gedruckt."
                )
            } else {
                toPrint.append(line)
            }
        }

        var jobs: [BonJob] = []
        let tables = Set(toPrint.map(\.tableNumber)).union(toCancel.map(\.line.tableNumber)).sorted()

        for table in tables {
            // Erst das Storno, dann die Nachbestellung: bei einer Mengen-
            // reduktion liest sich das in der Küche in der richtigen Reihenfolge.
            let cancelled = toCancel.filter { $0.line.tableNumber == table }
            if !cancelled.isEmpty {
                let lines = sortedByCreation(cancelled.map(\.line), in: delta)
                jobs.append(BonJob(
                    kind: .cancellation,
                    tableNumber: table,
                    bonNumber: state.nextBonNumber,
                    // Zeitpunkt der Stornierung, nicht der Bestellung: sonst steht
                    // auf einem eben gedruckten Zettel eine halbe Stunde alte Uhrzeit
                    // und die Küche hält ihn für einen Doppeldruck.
                    time: lines[0].voidedAt ?? lines[0].createdAt,
                    deviceName: deviceNames[lines[0].deviceId],
                    items: lines.map { BonJob.Item(qty: $0.qty, name: $0.nameSnapshot) },
                    originalBonNumbers: Set(cancelled.compactMap(\.originalBon)).sorted()
                ))
                for entry in cancelled {
                    state.lines[entry.line.id] = LineRecord(
                        status: .cancelled,
                        at: now,
                        bonNumber: entry.originalBon
                    )
                }
                state.nextBonNumber += 1
            }

            let ordered = sortedByCreation(toPrint.filter { $0.tableNumber == table }, in: delta)
            if !ordered.isEmpty {
                let bonNumber = state.nextBonNumber
                jobs.append(BonJob(
                    kind: .order,
                    tableNumber: table,
                    bonNumber: bonNumber,
                    time: ordered[0].createdAt,
                    deviceName: deviceNames[ordered[0].deviceId],
                    items: ordered.map { BonJob.Item(qty: $0.qty, name: $0.nameSnapshot) },
                    originalBonNumbers: []
                ))
                for line in ordered {
                    state.lines[line.id] = LineRecord(status: .printed, at: now, bonNumber: bonNumber)
                }
                state.nextBonNumber += 1
            }
        }

        return PlannerResult(jobs: jobs, state: state, warnings: warnings)
    }

    /// Rein wie `plan`, aus demselben Grund: die Altersgrenze und die
    /// Dublettensperre lassen sich sonst nur mit echter Uhr und echtem Server
    /// prüfen.
    ///
    /// `state.nextBonNumber` bleibt hier unangetastet — siehe `BonJob.bonNumber`.
    public static func planOverviews(
        requests: [PrintRequestDTO],
        deviceNames: [String: String],
        state: PrintState,
        config: BonConfig,
        now: Date
    ) -> PlannerResult {
        var state = state
        var warnings: [String] = []
        var jobs: [BonJob] = []

        let maxAge = TimeInterval(config.maxOverviewAgeMinutes * 60)

        for request in requests {
            // Der Auftrag kommt im nächsten Delta erneut (siehe Service.swift);
            // ohne diese Sperre bekäme der Gast alle zwei Sekunden einen Zettel.
            guard state.printRequests[request.id] == nil else { continue }

            guard config.printOverviews else {
                // Abgeschaltet heißt „nicht drucken“, nicht „später nochmal
                // ansehen“: ohne Vermerk stünde derselbe Auftrag bis zum Ende
                // der Serveraufbewahrung in jedem Delta.
                state.printRequests[request.id] = now
                continue
            }

            if now.timeIntervalSince(request.requestedAt) > maxAge {
                state.printRequests[request.id] = now
                warnings.append(
                    "Die Aufstellung für Tisch \(request.tableNumber) ist älter als "
                    + "\(config.maxOverviewAgeMinutes) Minuten und wurde nicht gedruckt."
                )
                continue
            }

            jobs.append(BonJob(
                kind: .overview,
                tableNumber: request.tableNumber,
                bonNumber: nil,
                // Zeitpunkt der Anforderung, nicht des Drucks: der Kellner
                // erkennt daran, ob der Zettel zu seinem letzten Tippen gehört.
                time: request.requestedAt,
                deviceName: deviceNames[request.deviceId],
                items: request.items.map {
                    BonJob.Item(qty: $0.qty, name: $0.name, unitPriceCents: $0.unitPriceCents)
                },
                totalCents: request.totalCents,
                footerText: config.footerText
            ))
        }

        return PlannerResult(jobs: jobs, state: state, warnings: warnings)
    }

    /// Positionen stehen in Buchungsreihenfolge auf dem Bon. Gleiche Zeitstempel
    /// entscheidet die Reihenfolge im Delta, damit die Ausgabe reproduzierbar ist.
    private static func sortedByCreation(_ lines: [OrderLineDTO], in delta: [OrderLineDTO]) -> [OrderLineDTO] {
        var position: [UUID: Int] = [:]
        for (index, line) in delta.enumerated() where position[line.id] == nil {
            position[line.id] = index
        }
        return lines.sorted { a, b in
            if a.createdAt != b.createdAt { return a.createdAt < b.createdAt }
            return (position[a.id] ?? 0) < (position[b.id] ?? 0)
        }
    }
}
