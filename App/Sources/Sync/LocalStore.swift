import Foundation
import KassaShared
import Observation
import SwiftData

enum ConnectionState: Equatable, Sendable {
    case connected
    case syncing
    case offline
    case needsLogin
}

/// Alles, was den lokalen Spiegel und die Offline-Queue anfasst, läuft hier auf
/// dem MainActor. Der `SyncEngine` ruft nur noch diese Methoden.
@MainActor
@Observable
final class LocalStore {
    let container: ModelContainer
    let settings: AppSettings

    var connection: ConnectionState = .offline
    var lastSyncAt: Date?
    var lastErrorMessage: String?
    /// Wird gesetzt, wenn ein Kassiervorgang serverseitig abgelehnt wurde.
    var conflictMessage: String?

    private var context: ModelContext { container.mainContext }
    private let encoder = KassaJSON.encoder
    private let decoder = KassaJSON.decoder

    init(container: ModelContainer, settings: AppSettings) {
        self.container = container
        self.settings = settings
    }

    // MARK: - Lesen

    func lines(forTable tableNumber: Int) -> [LocalOrderLine] {
        fetch(FetchDescriptor<LocalOrderLine>(
            predicate: #Predicate { $0.tableNumber == tableNumber },
            sortBy: [SortDescriptor(\.createdAt)]
        ))
    }

    func allLines() -> [LocalOrderLine] {
        fetch(FetchDescriptor<LocalOrderLine>(sortBy: [SortDescriptor(\.createdAt)]))
    }

    func articles() -> [LocalArticle] {
        fetch(FetchDescriptor<LocalArticle>(sortBy: [SortDescriptor(\.sortOrder), SortDescriptor(\.name)]))
    }

    func settlements() -> [LocalSettlement] {
        fetch(FetchDescriptor<LocalSettlement>(sortBy: [SortDescriptor(\.paidAt, order: .reverse)]))
    }

    func pendingCommands() -> [PendingCommand] {
        fetch(FetchDescriptor<PendingCommand>(sortBy: [SortDescriptor(\.createdAt)]))
    }

    var openPendingCount: Int {
        pendingCommands().count(where: { !$0.failedPermanently })
    }

    var failedPendingCount: Int {
        pendingCommands().count(where: \.failedPermanently)
    }

    private func fetch<T>(_ descriptor: FetchDescriptor<T>) -> [T] {
        (try? context.fetch(descriptor)) ?? []
    }

    // MARK: - Optimistisches Schreiben

    /// Bucht Artikel auf einen Tisch: sofort lokal sichtbar, gleichzeitig in die
    /// Queue. Die UI wartet nie auf das Netz.
    func addLines(tableNumber: Int, items: [(article: LocalArticle, qty: Int)]) {
        let effective = items.filter { $0.qty > 0 }
        guard !effective.isEmpty else { return }

        let now = Date.now
        var newLines: [NewOrderLine] = []

        for item in effective {
            let id = UUID()
            context.insert(LocalOrderLine(
                id: id,
                tableNumber: tableNumber,
                articleId: item.article.id,
                nameSnapshot: item.article.name,
                unitPriceCents: item.article.priceCents,
                qty: item.qty,
                createdAt: now,
                deviceId: settings.deviceId
            ))
            newLines.append(NewOrderLine(
                id: id,
                tableNumber: tableNumber,
                articleId: item.article.id,
                qty: item.qty,
                createdAt: now
            ))
        }

        enqueue(.addLines, payload: CreateOrderLinesRequest(lines: newLines))
        save()
    }

    func voidLine(_ line: LocalOrderLine) {
        guard line.isOpen else { return }
        line.voidedAt = .now
        enqueue(.voidLine, payload: VoidLineCommand(lineId: line.id))
        save()
    }

    /// Menge ändern. Der Vertrag kennt nur „ganze Zeile stornieren“, also wird
    /// beim Verringern die alte Zeile storniert und eine neue mit der Restmenge
    /// gebucht. Erhöhen ist einfach eine zusätzliche Buchung.
    func changeQty(of line: LocalOrderLine, to newQty: Int) {
        guard line.isOpen, newQty != line.qty else { return }

        if newQty > line.qty {
            let now = Date.now
            let id = UUID()
            context.insert(LocalOrderLine(
                id: id,
                tableNumber: line.tableNumber,
                articleId: line.articleId,
                nameSnapshot: line.nameSnapshot,
                unitPriceCents: line.unitPriceCents,
                qty: newQty - line.qty,
                createdAt: now,
                deviceId: settings.deviceId
            ))
            enqueue(.addLines, payload: CreateOrderLinesRequest(lines: [
                NewOrderLine(
                    id: id,
                    tableNumber: line.tableNumber,
                    articleId: line.articleId,
                    qty: newQty - line.qty,
                    createdAt: now
                )
            ]))
            save()
            return
        }

        let remaining = max(newQty, 0)
        let articleId = line.articleId
        let tableNumber = line.tableNumber
        let name = line.nameSnapshot
        let unitPrice = line.unitPriceCents

        line.voidedAt = .now
        enqueue(.voidLine, payload: VoidLineCommand(lineId: line.id))

        if remaining > 0 {
            let now = Date.now
            let id = UUID()
            context.insert(LocalOrderLine(
                id: id,
                tableNumber: tableNumber,
                articleId: articleId,
                nameSnapshot: name,
                unitPriceCents: unitPrice,
                qty: remaining,
                createdAt: now,
                deviceId: settings.deviceId
            ))
            enqueue(.addLines, payload: CreateOrderLinesRequest(lines: [
                NewOrderLine(id: id, tableNumber: tableNumber, articleId: articleId, qty: remaining, createdAt: now)
            ]))
        }
        save()
    }

    /// Kassieren. Voll kassierte Zeilen bekommen lokal die Settlement-ID,
    /// teilweise kassierte werden lokal um die Menge reduziert — der Server
    /// splittet die Zeile und liefert die Wahrheit beim nächsten Delta nach.
    @discardableResult
    func settle(tableNumber: Int, selections: [SettlementLineSelection], total: Money, tip: Money = .zero, paidAt: Date = .now) -> UUID {
        let settlementId = UUID()
        let byId = Dictionary(uniqueKeysWithValues: lines(forTable: tableNumber).map { ($0.id, $0) })

        for selection in selections {
            guard let line = byId[selection.lineId], line.isOpen else { continue }
            if selection.qty >= line.qty {
                line.settlementId = settlementId
            } else {
                line.qty -= selection.qty
            }
        }

        context.insert(LocalSettlement(
            id: settlementId,
            tableNumber: tableNumber,
            totalCents: total.cents,
            paidAt: paidAt,
            deviceId: settings.deviceId,
            businessDay: BusinessDay.day(for: paidAt, cutoffHour: settings.businessDayCutoffHour),
            tipCents: tip.cents
        ))

        enqueue(.settle, payload: CreateSettlementRequest(
            id: settlementId,
            tableNumber: tableNumber,
            lines: selections,
            amountCents: total.cents,
            paidAt: paidAt,
            tipCents: tip.cents
        ))
        save()
        return settlementId
    }

    // MARK: - Queue

    private func enqueue(_ kind: CommandKind, payload: some Encodable) {
        guard let data = try? encoder.encode(payload) else {
            lastErrorMessage = String(localized: "Buchung konnte nicht in die Warteschlange gelegt werden.")
            return
        }
        context.insert(PendingCommand(id: UUID(), kind: kind, payload: data))
    }

    /// FIFO. Dauerhaft fehlgeschlagene Commands werden übersprungen, sonst
    /// steht die ganze Queue hinter einem einzigen 4xx still.
    func nextPendingCommand() -> PendingCommandSnapshot? {
        guard let command = pendingCommands().first(where: { !$0.failedPermanently }),
              let kind = command.kind else { return nil }
        return PendingCommandSnapshot(
            id: command.id,
            kind: kind,
            payload: command.payload,
            createdAt: command.createdAt,
            attemptCount: command.attemptCount
        )
    }

    func completeCommand(id: UUID) {
        guard let command = pendingCommands().first(where: { $0.id == id }) else { return }
        context.delete(command)
        save()
    }

    func recordFailure(id: UUID, message: String, permanent: Bool) {
        guard let command = pendingCommands().first(where: { $0.id == id }) else { return }
        command.attemptCount += 1
        command.lastError = message
        command.failedPermanently = permanent
        save()
    }

    func discardCommand(id: UUID) {
        guard let command = pendingCommands().first(where: { $0.id == id }) else { return }
        // Ein verworfener Kassiervorgang darf lokal nicht als kassiert stehen
        // bleiben — der Server weiß nichts davon.
        if command.kind == .settle,
           let request = try? decoder.decode(CreateSettlementRequest.self, from: command.payload) {
            rollbackSettlement(id: request.id)
        }
        context.delete(command)
        save()
    }

    func rollbackSettlement(id settlementId: UUID) {
        for line in allLines() where line.settlementId == settlementId {
            line.settlementId = nil
        }
        for settlement in settlements() where settlement.id == settlementId && settlement.pendingLocal {
            context.delete(settlement)
        }
        save()
    }

    // MARK: - Merge

    func merge(_ response: SyncResponse) {
        if response.isFullReload {
            replaceMirror()
        }
        var linesById = Dictionary(allLines().map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for dto in response.lines {
            upsert(dto, existing: &linesById)
        }
        var settlementsById = Dictionary(settlements().map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        for dto in response.settlements {
            upsert(dto, existing: &settlementsById)
        }
        settings.maxSeq = max(settings.maxSeq, response.maxSeq)
        lastSyncAt = .now
        save()
    }

    /// Spiegel ersetzen heißt: alles wegwerfen, was vom Server kam. Noch nicht
    /// bestätigte lokale Buchungen und die PendingCommands bleiben — sonst
    /// verschwinden Bestellungen, die noch gar nicht abgesetzt wurden.
    private func replaceMirror() {
        for line in allLines() where !line.pendingLocal {
            context.delete(line)
        }
        for settlement in settlements() where !settlement.pendingLocal {
            context.delete(settlement)
        }
        settings.maxSeq = 0
    }

    private func upsert(_ dto: OrderLineDTO, existing: inout [UUID: LocalOrderLine]) {
        if let line = existing[dto.id] {
            // Ein älterer Serverstand darf einen neueren nicht zurückdrehen.
            guard dto.updatedSeq >= line.updatedSeq || line.pendingLocal else { return }
            line.apply(dto)
        } else {
            let line = LocalOrderLine(dto: dto)
            context.insert(line)
            existing[dto.id] = line
        }
    }

    private func upsert(_ dto: SettlementDTO, existing: inout [UUID: LocalSettlement]) {
        if let settlement = existing[dto.id] {
            guard dto.updatedSeq >= settlement.updatedSeq || settlement.pendingLocal else { return }
            settlement.apply(dto)
        } else {
            let settlement = LocalSettlement(dto: dto)
            context.insert(settlement)
            existing[dto.id] = settlement
        }
    }

    func replaceArticles(_ dtos: [ArticleDTO]) {
        let existing = Dictionary(articles().map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var seen: Set<String> = []
        for dto in dtos {
            seen.insert(dto.id)
            if let article = existing[dto.id] {
                article.apply(dto)
            } else {
                context.insert(LocalArticle(dto: dto))
            }
        }
        for (id, article) in existing where !seen.contains(id) {
            article.active = false
        }
        save()
    }

    // MARK: - Abmelden

    func wipeAll() {
        for line in allLines() { context.delete(line) }
        for settlement in settlements() { context.delete(settlement) }
        for command in pendingCommands() { context.delete(command) }
        for article in articles() { context.delete(article) }
        save()
    }

    private func save() {
        do {
            try context.save()
        } catch {
            lastErrorMessage = String(localized: "Lokal speichern fehlgeschlagen: \(error.localizedDescription)")
        }
    }
}
