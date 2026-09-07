import Fluent
import Foundation
import KassaShared
import Vapor

extension String {
    /// Die Routen-Konstanten im Shared-Package sind Pfade wie "orders/lines".
    var pathComponents: [PathComponent] {
        split(separator: "/").map { PathComponent(stringLiteral: String($0)) }
    }
}

func routes(_ app: Application) throws {
    app.post(APIRoute.login.pathComponents, use: login)

    let api = app.grouped(DeviceAuthMiddleware())
    api.get(APIRoute.config.pathComponents, use: config)
    api.get(APIRoute.articles.pathComponents, use: articles)
    api.get(APIRoute.devices.pathComponents, use: devices)
    api.get(APIRoute.sync.pathComponents, use: sync)
    api.post(APIRoute.orderLines.pathComponents, use: createOrderLines)
    api.post(APIRoute.orderLines.pathComponents + [":lineID", "void"], use: voidOrderLine)
    api.post(APIRoute.settlements.pathComponents, use: createSettlement)
    api.post(APIRoute.printRequests.pathComponents, use: createPrintRequest)
    api.get(APIRoute.printRequests.pathComponents, use: printRequests)
    api.get(APIRoute.dayReport.pathComponents, use: dayReport)

    api.webSocket(APIRoute.webSocket.pathComponents) { request, socket in
        // Mobilfunk-NAT wirft stille Verbindungen weg; der Heartbeat hält sie offen.
        socket.pingInterval = .seconds(30)
        let sockets = request.application.sockets
        let id = await sockets.add(socket)
        socket.onClose.whenComplete { _ in
            Task { await sockets.remove(id) }
        }
    }
}

// MARK: - Auth

@Sendable
private func login(request: Request) async throws -> LoginResponse {
    let body = try request.content.decode(LoginRequest.self)
    guard DeviceToken.passwordMatches(body.password, request.kassa.password) else {
        throw Abort(.unauthorized, reason: "Falsches Passwort")
    }

    let token = DeviceToken.generate()
    let now = Date()
    let device = Device(
        id: UUID().uuidString,
        name: body.deviceName.isEmpty ? "Unbenannt" : body.deviceName,
        tokenHash: DeviceToken.hash(token),
        createdAt: now,
        lastSeenAt: now
    )
    let db = request.db
    try await request.application.writeLock.run { try await device.create(on: db) }

    return LoginResponse(token: token, deviceId: try device.requireID())
}

// MARK: - Katalog

@Sendable
private func config(request: Request) async throws -> ServerConfigDTO {
    let state = try await ServerState.current(on: request.db)
    return ServerConfigDTO(
        tableCount: request.kassa.tableCount,
        businessDayCutoffHour: request.kassa.cutoffHour,
        catalogVersion: state.catalogVersion
    )
}

@Sendable
private func articles(request: Request) async throws -> [ArticleDTO] {
    try await Article.query(on: request.db)
        .filter(\.$active == true)
        .sort(\.$sortOrder)
        .all()
        .map { try $0.dto() }
}

/// Für den Kellner-Namen auf dem Bon — z.B. „Kellner: iPhone Anna".
@Sendable
private func devices(request: Request) async throws -> [DeviceDTO] {
    try await Device.query(on: request.db)
        .sort(\.$name)
        .all()
        .map { try $0.dto() }
}

// MARK: - Sync

@Sendable
private func sync(request: Request) async throws -> SyncResponse {
    let since = request.query[Int.self, at: "since"] ?? 0
    let db = request.db
    let maxSeq = try await ServerState.current(on: db).seq

    guard since > 0 else {
        // Voller Nachladevorgang: die letzten drei Betriebstage plus alles, was
        // noch offen ist — sonst wächst diese Antwort mit jedem Saisonabend.
        let start = retentionStart(now: Date(), cutoffHour: request.kassa.cutoffHour)
        let lines = try await OrderLine.query(on: db)
            .group(.or) { or in
                or.filter(\.$createdAt >= start)
                or.group(.and) { and in
                    and.filter(\.$voidedAt == nil)
                    and.filter(\.$settlementId == nil)
                }
            }
            .sort(\.$updatedSeq)
            .all()
        let settlements = try await Settlement.query(on: db)
            .filter(\.$paidAt >= start)
            .sort(\.$updatedSeq)
            .all()
        return SyncResponse(
            lines: try lines.map { try $0.dto() },
            settlements: try settlements.map { try $0.dto() },
            maxSeq: maxSeq,
            isFullReload: true
        )
    }

    let lines = try await OrderLine.query(on: db)
        .filter(\.$updatedSeq > since)
        .sort(\.$updatedSeq)
        .all()
    let settlements = try await Settlement.query(on: db)
        .filter(\.$updatedSeq > since)
        .sort(\.$updatedSeq)
        .all()
    return SyncResponse(
        lines: try lines.map { try $0.dto() },
        settlements: try settlements.map { try $0.dto() },
        maxSeq: maxSeq
    )
}

/// Beginn des drittletzten Betriebstags.
func retentionStart(now: Date, cutoffHour: Int) -> Date {
    let today = BusinessDay.day(for: now, cutoffHour: cutoffHour)
    guard let range = BusinessDay.range(for: today, cutoffHour: cutoffHour) else { return .distantPast }
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = BusinessDay.timeZone
    return calendar.date(byAdding: .day, value: -2, to: range.start) ?? range.start
}

// MARK: - Bestellzeilen

@Sendable
private func createOrderLines(request: Request) async throws -> [OrderLineDTO] {
    let body = try request.content.decode(CreateOrderLinesRequest.self)
    guard !body.lines.isEmpty else { return [] }

    let deviceId = try request.device.requireID()
    let tableCount = request.kassa.tableCount

    return try await request.mutate { db, seq in
        let articleIDs = Set(body.lines.map(\.articleId))
        let articles = try await Article.query(on: db).filter(\.$id ~~ Array(articleIDs)).all()
        let byID = Dictionary(uniqueKeysWithValues: try articles.map { (try $0.requireID(), $0) })

        var result: [OrderLineDTO] = []
        for new in body.lines {
            // Idempotent: derselbe Command aus der Offline-Queue darf nicht doppeln.
            if let existing = try await OrderLine.find(new.id, on: db) {
                result.append(try existing.dto())
                continue
            }
            guard let article = byID[new.articleId], article.active else {
                throw Abort(.badRequest, reason: "Unbekannter oder inaktiver Artikel: \(new.articleId)")
            }
            guard new.qty > 0 else {
                throw Abort(.badRequest, reason: "Menge muss größer als 0 sein")
            }
            guard (1...tableCount).contains(new.tableNumber) else {
                throw Abort(.badRequest, reason: "Tischnummer außerhalb 1...\(tableCount)")
            }

            // Name und Preis kommen vom Server, nicht vom Client.
            let line = OrderLine(
                id: new.id,
                tableNumber: new.tableNumber,
                articleId: new.articleId,
                nameSnapshot: article.name,
                unitPriceCents: article.priceCents,
                qty: new.qty,
                createdAt: new.createdAt,
                deviceId: deviceId,
                updatedSeq: seq
            )
            try await line.create(on: db)
            result.append(try line.dto())
        }
        return result
    }
}

@Sendable
private func voidOrderLine(request: Request) async throws -> Response {
    guard let lineID = request.parameters.get("lineID", as: UUID.self) else {
        throw Abort(.badRequest, reason: "Ungültige Zeilen-ID")
    }

    do {
        let dto = try await request.mutate { db, seq in
            guard let line = try await OrderLine.find(lineID, on: db) else {
                throw Abort(.notFound, reason: "Zeile nicht gefunden")
            }
            if line.voidedAt != nil { return try line.dto() } // schon storniert: idempotent
            if let settlementID = line.settlementId {
                throw SettlementConflict(
                    conflictingLineIds: [lineID],
                    settledByDevice: try await settlingDeviceName(of: settlementID, on: db),
                    reason: .alreadySettled
                )
            }
            line.voidedAt = Date()
            line.updatedSeq = seq
            try await line.save(on: db)
            return try line.dto()
        }
        return try await dto.encodeResponse(for: request)
    } catch let conflict as SettlementConflict {
        return try await conflict.response(for: request)
    }
}

// MARK: - Hilfen

struct SettlementConflict: Error {
    var conflictingLineIds: [UUID]
    var settledByDevice: String?
    var reason: SettlementConflictDTO.Reason

    func response(for request: Request) async throws -> Response {
        let dto = SettlementConflictDTO(
            conflictingLineIds: conflictingLineIds,
            settledByDevice: settledByDevice,
            reason: reason
        )
        let response = try await dto.encodeResponse(for: request)
        response.status = .conflict
        return response
    }
}

/// Der Client zeigt „Tisch wurde bereits von Gerät X kassiert" — dafür braucht
/// er den Gerätenamen, nicht die Geräte-ID.
func settlingDeviceName(of settlementID: UUID, on db: any Database) async throws -> String? {
    guard let settlement = try await Settlement.find(settlementID, on: db) else { return nil }
    return try await Device.find(settlement.deviceId, on: db)?.name
}
