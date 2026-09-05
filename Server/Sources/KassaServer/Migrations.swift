import Fluent
import SQLKit

struct CreateSchema: AsyncMigration {
    func prepare(on database: any Database) async throws {
        try await database.schema(Article.schema)
            .field(.id, .string, .identifier(auto: false))
            .field("category", .string, .required)
            .field("name", .string, .required)
            .field("price_cents", .int, .required)
            .field("sort_order", .int, .required)
            .field("active", .bool, .required)
            .create()

        try await database.schema(OrderLine.schema)
            .field(.id, .uuid, .identifier(auto: false))
            .field("table_number", .int, .required)
            .field("article_id", .string, .required)
            .field("name_snapshot", .string, .required)
            .field("unit_price_cents", .int, .required)
            .field("qty", .int, .required)
            .field("created_at", .datetime, .required)
            .field("device_id", .string, .required)
            .field("voided_at", .datetime)
            .field("settlement_id", .uuid)
            .field("updated_seq", .int, .required)
            .create()

        try await database.schema(Settlement.schema)
            .field(.id, .uuid, .identifier(auto: false))
            .field("table_number", .int, .required)
            .field("total_cents", .int, .required)
            .field("paid_at", .datetime, .required)
            .field("device_id", .string, .required)
            .field("business_day", .string, .required)
            .field("updated_seq", .int, .required)
            .create()

        try await database.schema(Device.schema)
            .field(.id, .string, .identifier(auto: false))
            .field("name", .string, .required)
            .field("token_hash", .string, .required)
            .field("created_at", .datetime, .required)
            .field("last_seen_at", .datetime, .required)
            .unique(on: "token_hash")
            .create()

        try await database.schema(ServerState.schema)
            .field(.id, .int, .identifier(auto: false))
            .field("seq", .int, .required)
            .field("catalog_version", .int, .required)
            .create()

        // Fluents Schema-Builder kann keine Sekundärindizes — die beiden
        // updated_seq-Spalten tragen aber jede /sync-Abfrage.
        if let sql = database as? any SQLDatabase {
            try await sql.raw("CREATE INDEX order_lines_updated_seq ON order_lines (updated_seq)").run()
            try await sql.raw("CREATE INDEX settlements_updated_seq ON settlements (updated_seq)").run()
            try await sql.raw("CREATE INDEX settlements_business_day ON settlements (business_day)").run()
            try await sql.raw("CREATE INDEX order_lines_settlement_id ON order_lines (settlement_id)").run()
        }

        try await ServerState(seq: 0, catalogVersion: 0).create(on: database)
    }

    func revert(on database: any Database) async throws {
        try await database.schema(ServerState.schema).delete()
        try await database.schema(Device.schema).delete()
        try await database.schema(Settlement.schema).delete()
        try await database.schema(OrderLine.schema).delete()
        try await database.schema(Article.schema).delete()
    }
}
