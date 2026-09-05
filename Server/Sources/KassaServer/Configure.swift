import Fluent
import FluentSQLiteDriver
import Foundation
import KassaShared
import SQLKit
import Vapor

public func configureFromEnvironment(_ app: Application) async throws {
    try await configure(app, kassa: KassaConfig.fromEnvironment())
}

func configure(_ app: Application, kassa: KassaConfig) async throws {
    // Muss zu KassaJSON im Shared-Package passen, sonst verschieben sich alle
    // Zeitstempel zwischen App und Server.
    ContentConfiguration.global.use(encoder: KassaJSON.encoder, for: .json)
    ContentConfiguration.global.use(decoder: KassaJSON.decoder, for: .json)

    app.kassa = kassa
    app.http.server.configuration.hostname = "0.0.0.0"
    app.http.server.configuration.port = kassa.port

    switch kassa.storage {
    case .file(let path):
        app.databases.use(.sqlite(.file(path)), as: .sqlite)
    case .memory:
        app.databases.use(.sqlite(.memory), as: .sqlite)
    }

    // WAL: Leser blockieren den Schreiber nicht mehr. Der Modus steckt im
    // Dateikopf, einmal setzen genügt.
    if case .file = kassa.storage, let sql = app.db(.sqlite) as? any SQLDatabase {
        try await sql.raw("PRAGMA journal_mode=WAL").run()
    }

    app.migrations.add(CreateSchema())
    try await app.autoMigrate()

    app.asyncCommands.use(ImportPricesCommand(), as: "import-prices")

    try routes(app)

    var articleCount = try await Article.query(on: app.db).count()
    if articleCount == 0, let path = kassa.priceListPath {
        if FileManager.default.fileExists(atPath: path) {
            articleCount = try await PriceList.importFile(at: path, on: app.db, lock: app.writeLock)
            app.logger.notice("Preisliste erstimportiert: \(articleCount) Artikel aus \(path)")
        } else {
            app.logger.warning("Keine Artikel in der Datenbank und keine Preisliste unter \(path)")
        }
    }

    let state = try await ServerState.current(on: app.db)
    app.logger.notice("""
        Kassa-Server bereit — tische=\(kassa.tableCount) \
        betriebstagCutoff=\(kassa.cutoffHour) \
        datenbank=\(kassa.storage) port=\(kassa.port) \
        artikel=\(articleCount) catalogVersion=\(state.catalogVersion) seq=\(state.seq)
        """)
}
