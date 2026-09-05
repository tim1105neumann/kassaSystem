#if DEBUG
import Foundation
import KassaShared
import SwiftData

/// Beispieldaten für die SwiftUI-Previews. In-Memory, damit nichts auf Platte
/// landet.
@MainActor
enum PreviewData {
    struct Sample {
        let container: ModelContainer
        let model: AppModel
    }

    static func make() -> Sample {
        let schema = Schema([LocalArticle.self, LocalOrderLine.self, LocalSettlement.self, PendingCommand.self])
        let configuration = ModelConfiguration(isStoredInMemoryOnly: true)
        // Previews dürfen crashen — das ist kein Produktionspfad.
        let container = try! ModelContainer(for: schema, configurations: configuration)
        let context = container.mainContext

        let articles = [
            LocalArticle(id: "a1", category: "Speisen", name: "Käsekrainer mit Gebäck", priceCents: 620, sortOrder: 0, active: true),
            LocalArticle(id: "a2", category: "Speisen", name: "Bratwurst mit Pommes", priceCents: 920, sortOrder: 1, active: true),
            LocalArticle(id: "a3", category: "Speisen", name: "Portion Pommes", priceCents: 350, sortOrder: 2, active: true),
            LocalArticle(id: "b1", category: "Getraenke", name: "Bier, Radler 0,5 l", priceCents: 440, sortOrder: 10, active: true),
            LocalArticle(id: "b2", category: "Getraenke", name: "Limo (Cola, Frucade, Almdudler)", priceCents: 310, sortOrder: 11, active: true),
            LocalArticle(id: "b3", category: "Getraenke", name: "Leitungswasser", priceCents: 0, sortOrder: 12, active: true)
        ]
        articles.forEach { context.insert($0) }

        let start = Date.now.addingTimeInterval(-1_800)
        context.insert(LocalOrderLine(
            id: UUID(), tableNumber: 3, articleId: "a1", nameSnapshot: "Käsekrainer mit Gebäck",
            unitPriceCents: 620, qty: 2, createdAt: start, deviceId: "preview", updatedSeq: 1, pendingLocal: false
        ))
        context.insert(LocalOrderLine(
            id: UUID(), tableNumber: 3, articleId: "b1", nameSnapshot: "Bier, Radler 0,5 l",
            unitPriceCents: 440, qty: 3, createdAt: start, deviceId: "preview", updatedSeq: 2, pendingLocal: false
        ))
        context.insert(LocalOrderLine(
            id: UUID(), tableNumber: 7, articleId: "a2", nameSnapshot: "Bratwurst mit Pommes",
            unitPriceCents: 920, qty: 1, createdAt: start, deviceId: "preview", updatedSeq: 3, pendingLocal: false
        ))

        let settings = AppSettings(defaults: UserDefaults(suiteName: "preview") ?? .standard)
        settings.tableCount = 40
        let model = AppModel(container: container, settings: settings)
        return Sample(container: container, model: model)
    }
}
#endif
