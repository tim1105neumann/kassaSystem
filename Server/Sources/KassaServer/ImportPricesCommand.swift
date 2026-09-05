import Vapor

struct ImportPricesCommand: AsyncCommand {
    struct Signature: CommandSignature {
        @Argument(name: "path", help: "Pfad zur Preisliste (CSV)")
        var path: String
    }

    let help = "Importiert die Preisliste aus einer CSV-Datei."

    func run(using context: CommandContext, signature: Signature) async throws {
        let app = context.application
        let count = try await PriceList.importFile(at: signature.path, on: app.db, lock: app.writeLock)
        let version = try await ServerState.current(on: app.db).catalogVersion
        context.console.info("\(count) Artikel importiert, catalogVersion=\(version).")
    }
}
