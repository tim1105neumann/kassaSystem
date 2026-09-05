import Foundation
import KassaShared
import SwiftData

// MARK: - Artikelkatalog

@Model
final class LocalArticle {
    @Attribute(.unique) var id: String
    var category: String
    var name: String
    var priceCents: Int
    var sortOrder: Int
    var active: Bool

    init(id: String, category: String, name: String, priceCents: Int, sortOrder: Int, active: Bool) {
        self.id = id
        self.category = category
        self.name = name
        self.priceCents = priceCents
        self.sortOrder = sortOrder
        self.active = active
    }

    convenience init(dto: ArticleDTO) {
        self.init(
            id: dto.id,
            category: dto.category,
            name: dto.name,
            priceCents: dto.priceCents,
            sortOrder: dto.sortOrder,
            active: dto.active
        )
    }

    func apply(_ dto: ArticleDTO) {
        category = dto.category
        name = dto.name
        priceCents = dto.priceCents
        sortOrder = dto.sortOrder
        active = dto.active
    }

    var price: Money { Money(cents: priceCents) }
}

// MARK: - Bestellzeilen

@Model
final class LocalOrderLine {
    @Attribute(.unique) var id: UUID
    var tableNumber: Int
    var articleId: String
    var nameSnapshot: String
    var unitPriceCents: Int
    var qty: Int
    var createdAt: Date
    var deviceId: String
    var voidedAt: Date?
    var settlementId: UUID?
    var updatedSeq: Int
    /// Optimistisch lokal angelegt und vom Server noch nicht bestätigt. Solche
    /// Zeilen überlebt ein Full Reload, sonst würde die Buchung verschwinden,
    /// bevor die Queue sie überhaupt abgesetzt hat.
    var pendingLocal: Bool

    init(
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
        updatedSeq: Int = 0,
        pendingLocal: Bool = true
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
        self.pendingLocal = pendingLocal
    }

    convenience init(dto: OrderLineDTO) {
        self.init(
            id: dto.id,
            tableNumber: dto.tableNumber,
            articleId: dto.articleId,
            nameSnapshot: dto.nameSnapshot,
            unitPriceCents: dto.unitPriceCents,
            qty: dto.qty,
            createdAt: dto.createdAt,
            deviceId: dto.deviceId,
            voidedAt: dto.voidedAt,
            settlementId: dto.settlementId,
            updatedSeq: dto.updatedSeq,
            pendingLocal: false
        )
    }

    func apply(_ dto: OrderLineDTO) {
        tableNumber = dto.tableNumber
        articleId = dto.articleId
        nameSnapshot = dto.nameSnapshot
        unitPriceCents = dto.unitPriceCents
        qty = dto.qty
        createdAt = dto.createdAt
        deviceId = dto.deviceId
        voidedAt = dto.voidedAt
        settlementId = dto.settlementId
        updatedSeq = dto.updatedSeq
        pendingLocal = false
    }

    var unitPrice: Money { Money(cents: unitPriceCents) }
    var lineTotal: Money { Money(cents: unitPriceCents * qty) }
    /// Offen = weder storniert noch kassiert.
    var isOpen: Bool { voidedAt == nil && settlementId == nil }
}

// MARK: - Kassiervorgänge

@Model
final class LocalSettlement {
    @Attribute(.unique) var id: UUID
    var tableNumber: Int
    var totalCents: Int
    var paidAt: Date
    var deviceId: String
    var businessDay: String
    var updatedSeq: Int
    var pendingLocal: Bool

    init(
        id: UUID,
        tableNumber: Int,
        totalCents: Int,
        paidAt: Date,
        deviceId: String,
        businessDay: String,
        updatedSeq: Int = 0,
        pendingLocal: Bool = true
    ) {
        self.id = id
        self.tableNumber = tableNumber
        self.totalCents = totalCents
        self.paidAt = paidAt
        self.deviceId = deviceId
        self.businessDay = businessDay
        self.updatedSeq = updatedSeq
        self.pendingLocal = pendingLocal
    }

    convenience init(dto: SettlementDTO) {
        self.init(
            id: dto.id,
            tableNumber: dto.tableNumber,
            totalCents: dto.totalCents,
            paidAt: dto.paidAt,
            deviceId: dto.deviceId,
            businessDay: dto.businessDay,
            updatedSeq: dto.updatedSeq,
            pendingLocal: false
        )
    }

    func apply(_ dto: SettlementDTO) {
        tableNumber = dto.tableNumber
        totalCents = dto.totalCents
        paidAt = dto.paidAt
        deviceId = dto.deviceId
        businessDay = dto.businessDay
        updatedSeq = dto.updatedSeq
        pendingLocal = false
    }

    var total: Money { Money(cents: totalCents) }
}

// MARK: - Offline-Queue

enum CommandKind: String, Codable, Sendable {
    case addLines
    case voidLine
    case settle
}

/// Kein DTO im Shared-Package für das Stornieren — die Route trägt die ID im
/// Pfad. Für die Queue brauchen wir sie aber als Payload.
struct VoidLineCommand: Codable, Sendable {
    var lineId: UUID
}

@Model
final class PendingCommand {
    @Attribute(.unique) var id: UUID
    var kindRaw: String
    var payload: Data
    var createdAt: Date
    var attemptCount: Int
    var lastError: String?
    /// Ein 4xx wird sich nicht von selbst heilen. Solche Commands bleiben zur
    /// Ansicht in den Einstellungen liegen, blockieren aber die FIFO nicht.
    var failedPermanently: Bool

    init(id: UUID, kind: CommandKind, payload: Data, createdAt: Date = .now) {
        self.id = id
        self.kindRaw = kind.rawValue
        self.payload = payload
        self.createdAt = createdAt
        self.attemptCount = 0
        self.lastError = nil
        self.failedPermanently = false
    }

    var kind: CommandKind? { CommandKind(rawValue: kindRaw) }
}

/// Sendable-Kopie, damit der `SyncEngine`-Actor nicht auf das SwiftData-Objekt
/// zugreifen muss (das gehört dem MainActor).
struct PendingCommandSnapshot: Sendable, Identifiable {
    var id: UUID
    var kind: CommandKind
    var payload: Data
    var createdAt: Date
    var attemptCount: Int
}
