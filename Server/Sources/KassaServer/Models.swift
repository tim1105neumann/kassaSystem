import Fluent
import Foundation
import KassaShared
import Vapor

// Fluent-Modelle sind veränderliche Klassen; Fluent selbst garantiert, dass eine
// Instanz nur von einem Task benutzt wird. Daher @unchecked Sendable.

final class Article: Model, @unchecked Sendable {
    static let schema = "articles"

    @ID(custom: .id, generatedBy: .user) var id: String?
    @Field(key: "category") var category: String
    @Field(key: "name") var name: String
    @Field(key: "price_cents") var priceCents: Int
    @Field(key: "sort_order") var sortOrder: Int
    @Field(key: "active") var active: Bool

    init() {}

    init(id: String, category: String, name: String, priceCents: Int, sortOrder: Int, active: Bool = true) {
        self.id = id
        self.category = category
        self.name = name
        self.priceCents = priceCents
        self.sortOrder = sortOrder
        self.active = active
    }

    func dto() throws -> ArticleDTO {
        ArticleDTO(
            id: try requireID(),
            category: category,
            name: name,
            priceCents: priceCents,
            sortOrder: sortOrder,
            active: active
        )
    }
}

final class OrderLine: Model, @unchecked Sendable {
    static let schema = "order_lines"

    @ID(custom: .id, generatedBy: .user) var id: UUID?
    @Field(key: "table_number") var tableNumber: Int
    @Field(key: "article_id") var articleId: String
    @Field(key: "name_snapshot") var nameSnapshot: String
    @Field(key: "unit_price_cents") var unitPriceCents: Int
    @Field(key: "qty") var qty: Int
    @Field(key: "created_at") var createdAt: Date
    @Field(key: "device_id") var deviceId: String
    @OptionalField(key: "voided_at") var voidedAt: Date?
    @OptionalField(key: "settlement_id") var settlementId: UUID?
    @Field(key: "updated_seq") var updatedSeq: Int

    init() {}

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

    func dto() throws -> OrderLineDTO {
        OrderLineDTO(
            id: try requireID(),
            tableNumber: tableNumber,
            articleId: articleId,
            nameSnapshot: nameSnapshot,
            unitPriceCents: unitPriceCents,
            qty: qty,
            createdAt: createdAt,
            deviceId: deviceId,
            voidedAt: voidedAt,
            settlementId: settlementId,
            updatedSeq: updatedSeq
        )
    }
}

final class Settlement: Model, @unchecked Sendable {
    static let schema = "settlements"

    @ID(custom: .id, generatedBy: .user) var id: UUID?
    @Field(key: "table_number") var tableNumber: Int
    @Field(key: "total_cents") var totalCents: Int
    @Field(key: "paid_at") var paidAt: Date
    @Field(key: "device_id") var deviceId: String
    @Field(key: "business_day") var businessDay: String
    @Field(key: "updated_seq") var updatedSeq: Int
    /// Trinkgeld, getrennt vom Umsatz — `total_cents` bleibt die Zeilensumme.
    @Field(key: "tip_cents") var tipCents: Int

    init() {}

    init(
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

    func dto() throws -> SettlementDTO {
        SettlementDTO(
            id: try requireID(),
            tableNumber: tableNumber,
            totalCents: totalCents,
            paidAt: paidAt,
            deviceId: deviceId,
            businessDay: businessDay,
            updatedSeq: updatedSeq,
            tipCents: tipCents
        )
    }
}

final class PrintRequest: Model, @unchecked Sendable {
    static let schema = "print_requests"

    @ID(custom: .id, generatedBy: .user) var id: UUID?
    @Field(key: "table_number") var tableNumber: Int
    /// Die Positionen sind ein eingefrorener Schnappschuss: sie werden nie
    /// einzeln abgefragt, gefiltert oder sortiert, sondern nur als Ganzes
    /// gedruckt. Eine zweite Tabelle mit Fremdschlüssel wäre für Daten, die
    /// einmal auf Papier gehen und danach nur noch Beleg sind, unverhältnismäßig.
    @Field(key: "items_json") var itemsJSON: String
    @Field(key: "total_cents") var totalCents: Int
    @Field(key: "requested_at") var requestedAt: Date
    @Field(key: "device_id") var deviceId: String
    @Field(key: "updated_seq") var updatedSeq: Int

    init() {}

    init(
        id: UUID,
        tableNumber: Int,
        items: [PrintRequestDTO.Item],
        totalCents: Int,
        requestedAt: Date,
        deviceId: String,
        updatedSeq: Int
    ) throws {
        self.id = id
        self.tableNumber = tableNumber
        self.itemsJSON = try Self.encodeItems(items)
        self.totalCents = totalCents
        self.requestedAt = requestedAt
        self.deviceId = deviceId
        self.updatedSeq = updatedSeq
    }

    func dto() throws -> PrintRequestDTO {
        PrintRequestDTO(
            id: try requireID(),
            tableNumber: tableNumber,
            items: try Self.decodeItems(itemsJSON),
            totalCents: totalCents,
            requestedAt: requestedAt,
            deviceId: deviceId,
            updatedSeq: updatedSeq
        )
    }

    // Kodieren und Dekodieren stehen bewusst nebeneinander, damit die Symmetrie
    // nicht auseinanderdriftet. KassaJSON bleibt außen vor: das ist die
    // Verabredung für die Leitung zwischen App und Server, der Blob dagegen
    // Datenbank-Interna und liest sich nur selbst wieder ein.
    private static func encodeItems(_ items: [PrintRequestDTO.Item]) throws -> String {
        String(decoding: try JSONEncoder().encode(items), as: UTF8.self)
    }

    private static func decodeItems(_ json: String) throws -> [PrintRequestDTO.Item] {
        try JSONDecoder().decode([PrintRequestDTO.Item].self, from: Data(json.utf8))
    }
}

final class Device: Model, @unchecked Sendable {
    static let schema = "devices"

    @ID(custom: .id, generatedBy: .user) var id: String?
    @Field(key: "name") var name: String
    @Field(key: "token_hash") var tokenHash: String
    @Field(key: "created_at") var createdAt: Date
    @Field(key: "last_seen_at") var lastSeenAt: Date

    init() {}

    init(id: String, name: String, tokenHash: String, createdAt: Date, lastSeenAt: Date) {
        self.id = id
        self.name = name
        self.tokenHash = tokenHash
        self.createdAt = createdAt
        self.lastSeenAt = lastSeenAt
    }

    /// Baut das DTO explizit aus id/name — token_hash darf die Antwort nie verlassen.
    func dto() throws -> DeviceDTO {
        DeviceDTO(id: try requireID(), name: name)
    }
}

/// Eine einzige Zeile. Trägt den globalen Sequenzzähler und die Katalogversion —
/// beides ist Server-weiter Zustand, für den sich zwei Tabellen nicht lohnen.
final class ServerState: Model, @unchecked Sendable {
    static let schema = "server_state"
    static let singletonID = 1

    @ID(custom: .id, generatedBy: .user) var id: Int?
    @Field(key: "seq") var seq: Int
    @Field(key: "catalog_version") var catalogVersion: Int

    init() {}

    init(seq: Int, catalogVersion: Int) {
        self.id = Self.singletonID
        self.seq = seq
        self.catalogVersion = catalogVersion
    }

    static func current(on db: any Database) async throws -> ServerState {
        guard let state = try await ServerState.find(singletonID, on: db) else {
            throw Abort(.internalServerError, reason: "server_state fehlt")
        }
        return state
    }
}
