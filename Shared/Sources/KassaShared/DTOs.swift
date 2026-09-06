import Foundation

// MARK: - Katalog

public struct ArticleDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var category: String
    public var name: String
    public var priceCents: Int
    public var sortOrder: Int
    public var active: Bool

    public init(id: String, category: String, name: String, priceCents: Int, sortOrder: Int, active: Bool) {
        self.id = id
        self.category = category
        self.name = name
        self.priceCents = priceCents
        self.sortOrder = sortOrder
        self.active = active
    }

    public var price: Money { Money(cents: priceCents) }
}

// MARK: - Bestellungen

public struct OrderLineDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: UUID
    public var tableNumber: Int
    public var articleId: String
    /// Name und Preis werden bei der Buchung eingefroren: eine spätere
    /// Preisänderung darf alte Rechnungen nicht rückwirkend verändern.
    public var nameSnapshot: String
    public var unitPriceCents: Int
    public var qty: Int
    public var createdAt: Date
    public var deviceId: String
    public var voidedAt: Date?
    public var settlementId: UUID?
    public var updatedSeq: Int

    public init(
        id: UUID,
        tableNumber: Int,
        articleId: String,
        nameSnapshot: String,
        unitPriceCents: Int,
        qty: Int,
        createdAt: Date,
        deviceId: String,
        voidedAt: Date? = nil,
        settlementId: UUID? = nil,
        updatedSeq: Int
    ) {
        self.id = id
        self.tableNumber = tableNumber
        self.articleId = articleId
        self.nameSnapshot = nameSnapshot
        self.unitPriceCents = unitPriceCents
        self.qty = qty
        self.createdAt = createdAt
        self.deviceId = deviceId
        self.voidedAt = voidedAt
        self.settlementId = settlementId
        self.updatedSeq = updatedSeq
    }

    public var lineTotal: Money { Money(cents: unitPriceCents * qty) }
    /// Offen = weder storniert noch kassiert.
    public var isOpen: Bool { voidedAt == nil && settlementId == nil }
}

public struct NewOrderLine: Codable, Hashable, Sendable {
    /// Vom Client vergeben — macht das Anlegen idempotent, auch wenn die
    /// Offline-Queue denselben Command mehrfach schickt.
    public var id: UUID
    public var tableNumber: Int
    public var articleId: String
    public var qty: Int
    public var createdAt: Date

    public init(id: UUID, tableNumber: Int, articleId: String, qty: Int, createdAt: Date) {
        self.id = id
        self.tableNumber = tableNumber
        self.articleId = articleId
        self.qty = qty
        self.createdAt = createdAt
    }
}

public struct CreateOrderLinesRequest: Codable, Sendable {
    public var lines: [NewOrderLine]
    public init(lines: [NewOrderLine]) { self.lines = lines }
}

// MARK: - Kassieren

public struct SettlementLineSelection: Codable, Hashable, Sendable {
    public var lineId: UUID
    /// Teilmenge: kleiner als `qty` der Zeile ist erlaubt, der Server splittet dann.
    public var qty: Int

    public init(lineId: UUID, qty: Int) {
        self.lineId = lineId
        self.qty = qty
    }
}

public struct CreateSettlementRequest: Codable, Sendable {
    public var id: UUID
    public var tableNumber: Int
    public var lines: [SettlementLineSelection]
    /// Vom Client errechnete Summe. Der Server rechnet selbst nach und lehnt
    /// bei Abweichung ab — schützt vor veraltetem Client-Stand.
    public var amountCents: Int
    public var paidAt: Date
    /// Aufrundung des Gastes. Steht bewusst neben `amountCents` und nicht darin,
    /// damit die Nachrechnung am Server weiterhin die reine Zeilensumme prüft.
    public var tipCents: Int

    public init(
        id: UUID,
        tableNumber: Int,
        lines: [SettlementLineSelection],
        amountCents: Int,
        paidAt: Date,
        tipCents: Int = 0
    ) {
        self.id = id
        self.tableNumber = tableNumber
        self.lines = lines
        self.amountCents = amountCents
        self.paidAt = paidAt
        self.tipCents = tipCents
    }

    /// Die Offline-Queue legt diesen Request als rohes `Data` ab. Nach einem
    /// App-Update muss eine alte, noch nicht gesendete Zahlung ohne `tipCents`
    /// weiterhin dekodierbar sein — sonst bleibt sie für immer liegen.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try container.decode(UUID.self, forKey: .id)
        self.tableNumber = try container.decode(Int.self, forKey: .tableNumber)
        self.lines = try container.decode([SettlementLineSelection].self, forKey: .lines)
        self.amountCents = try container.decode(Int.self, forKey: .amountCents)
        self.paidAt = try container.decode(Date.self, forKey: .paidAt)
        self.tipCents = try container.decodeIfPresent(Int.self, forKey: .tipCents) ?? 0
    }
}

public struct SettlementDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: UUID
    public var tableNumber: Int
    public var totalCents: Int
    public var paidAt: Date
    public var deviceId: String
    /// Betriebstag als `yyyy-MM-dd` — nicht der Kalendertag, siehe Cutoff.
    public var businessDay: String
    public var updatedSeq: Int
    /// Getrennt vom Umsatz, damit `totalCents` weiter gegen die Zeilen aufgeht.
    public var tipCents: Int

    public init(
        id: UUID,
        tableNumber: Int,
        totalCents: Int,
        paidAt: Date,
        deviceId: String,
        businessDay: String,
        updatedSeq: Int,
        tipCents: Int = 0
    ) {
        self.id = id
        self.tableNumber = tableNumber
        self.totalCents = totalCents
        self.paidAt = paidAt
        self.deviceId = deviceId
        self.businessDay = businessDay
        self.updatedSeq = updatedSeq
        self.tipCents = tipCents
    }

    /// Toleranter Decode wie beim Request: ein Client mit älterem Stand darf
    /// eine Antwort ohne `tipCents` nicht als kaputt ansehen.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try container.decode(UUID.self, forKey: .id)
        self.tableNumber = try container.decode(Int.self, forKey: .tableNumber)
        self.totalCents = try container.decode(Int.self, forKey: .totalCents)
        self.paidAt = try container.decode(Date.self, forKey: .paidAt)
        self.deviceId = try container.decode(String.self, forKey: .deviceId)
        self.businessDay = try container.decode(String.self, forKey: .businessDay)
        self.updatedSeq = try container.decode(Int.self, forKey: .updatedSeq)
        self.tipCents = try container.decodeIfPresent(Int.self, forKey: .tipCents) ?? 0
    }

    public var total: Money { Money(cents: totalCents) }
    public var tip: Money { Money(cents: tipCents) }
    /// Umsatz + Trinkgeld — was tatsächlich in der Kassa liegt.
    public var grandTotal: Money { Money(cents: totalCents + tipCents) }
}

/// Antwortkörper bei HTTP 409 auf `POST /settlements`.
public struct SettlementConflictDTO: Codable, Sendable {
    public var conflictingLineIds: [UUID]
    public var settledByDevice: String?
    public var reason: Reason

    public enum Reason: String, Codable, Sendable {
        case alreadySettled
        case lineVoided
        case amountMismatch
        case unknownLine
        case quantityExceeded
    }

    public init(conflictingLineIds: [UUID], settledByDevice: String?, reason: Reason) {
        self.conflictingLineIds = conflictingLineIds
        self.settledByDevice = settledByDevice
        self.reason = reason
    }
}

// MARK: - Sync

public struct SyncResponse: Codable, Sendable {
    public var lines: [OrderLineDTO]
    public var settlements: [SettlementDTO]
    public var maxSeq: Int
    /// Signalisiert, dass `since` zu alt war und der Client komplett neu laden muss.
    public var isFullReload: Bool

    public init(lines: [OrderLineDTO], settlements: [SettlementDTO], maxSeq: Int, isFullReload: Bool = false) {
        self.lines = lines
        self.settlements = settlements
        self.maxSeq = maxSeq
        self.isFullReload = isFullReload
    }
}

/// Einzige Nachricht über den WebSocket. Er trägt bewusst keinen State, sondern
/// sagt nur "es gibt Neues" — der Client holt sich das Delta dann über /sync.
public struct SyncPing: Codable, Sendable {
    public var seq: Int
    public init(seq: Int) { self.seq = seq }
}

// MARK: - Auth & Konfiguration

public struct LoginRequest: Codable, Sendable {
    public var password: String
    public var deviceName: String
    public init(password: String, deviceName: String) {
        self.password = password
        self.deviceName = deviceName
    }
}

public struct LoginResponse: Codable, Sendable {
    public var token: String
    public var deviceId: String
    public init(token: String, deviceId: String) {
        self.token = token
        self.deviceId = deviceId
    }
}

public struct ServerConfigDTO: Codable, Sendable {
    public var tableCount: Int
    /// Ab welcher Stunde ein neuer Betriebstag beginnt (Default 6).
    public var businessDayCutoffHour: Int
    /// Ändert sich bei jedem CSV-Import — der Client lädt den Katalog dann neu.
    public var catalogVersion: Int

    public init(tableCount: Int, businessDayCutoffHour: Int, catalogVersion: Int) {
        self.tableCount = tableCount
        self.businessDayCutoffHour = businessDayCutoffHour
        self.catalogVersion = catalogVersion
    }
}

/// Geraetename fuer die Anzeige — der Kuechenbon zeigt damit, wer bestellt hat.
/// Bewusst ohne Token-Hash: der verlaesst den Server nie.
public struct DeviceDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var name: String

    public init(id: String, name: String) {
        self.id = id
        self.name = name
    }
}

// MARK: - Tagesabschluss

public struct DayReportDTO: Codable, Sendable {
    public var businessDay: String
    /// Reiner Warenumsatz — die Kategoriesummen gehen exakt darauf auf.
    public var totalCents: Int
    public var settlementCount: Int
    public var byCategory: [CategoryTotal]
    public var topArticles: [ArticleTotal]
    public var settlements: [SettlementDTO]
    public var tipCents: Int

    public struct CategoryTotal: Codable, Hashable, Sendable {
        public var category: String
        public var totalCents: Int
        public init(category: String, totalCents: Int) {
            self.category = category
            self.totalCents = totalCents
        }
    }

    public struct ArticleTotal: Codable, Hashable, Sendable {
        public var articleId: String
        public var name: String
        public var qty: Int
        public var totalCents: Int
        public init(articleId: String, name: String, qty: Int, totalCents: Int) {
            self.articleId = articleId
            self.name = name
            self.qty = qty
            self.totalCents = totalCents
        }
    }

    public init(
        businessDay: String,
        totalCents: Int,
        settlementCount: Int,
        byCategory: [CategoryTotal],
        topArticles: [ArticleTotal],
        settlements: [SettlementDTO],
        tipCents: Int = 0
    ) {
        self.businessDay = businessDay
        self.totalCents = totalCents
        self.settlementCount = settlementCount
        self.byCategory = byCategory
        self.topArticles = topArticles
        self.settlements = settlements
        self.tipCents = tipCents
    }

    /// Toleranter Decode: ein Bericht von einem Server ohne Trinkgeld-Feld
    /// bleibt lesbar.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.businessDay = try container.decode(String.self, forKey: .businessDay)
        self.totalCents = try container.decode(Int.self, forKey: .totalCents)
        self.settlementCount = try container.decode(Int.self, forKey: .settlementCount)
        self.byCategory = try container.decode([CategoryTotal].self, forKey: .byCategory)
        self.topArticles = try container.decode([ArticleTotal].self, forKey: .topArticles)
        self.settlements = try container.decode([SettlementDTO].self, forKey: .settlements)
        self.tipCents = try container.decodeIfPresent(Int.self, forKey: .tipCents) ?? 0
    }

    public var total: Money { Money(cents: totalCents) }
    public var tip: Money { Money(cents: tipCents) }
    /// Umsatz + Trinkgeld — was tatsächlich in der Kassa liegt.
    public var grandTotal: Money { Money(cents: totalCents + tipCents) }
}

// MARK: - Fehler

public struct APIErrorDTO: Codable, Sendable {
    public var reason: String
    public init(reason: String) { self.reason = reason }
}
