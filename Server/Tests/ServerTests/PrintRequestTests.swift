import Fluent
import Foundation
import KassaShared
import Testing
import VaporTesting
@testable import KassaServer

@Suite("Druckauftrag")
struct PrintRequestTests {
    /// Bucht `qty` Berner Würstel mit Gebäck (6,20 €) auf einen Tisch.
    private func bookBerner(_ harness: Harness, token: String, table: Int, qty: Int) async throws -> UUID {
        let article = try await harness.article(named: "Berner Würstel mit Gebäck", token: token)
        let id = UUID()
        try await harness.book(
            [NewOrderLine(id: id, tableNumber: table, articleId: article.id, qty: qty, createdAt: Date())],
            token: token
        )
        return id
    }

    @Test("Positionen und Summe rechnet der Server aus den Zeilen")
    func serverCalculatesItemsAndTotal() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let lineID = try await bookBerner(harness, token: token, table: 4, qty: 3)

            let response = try await harness.send(.POST, APIRoute.printRequests, token: token, body: CreatePrintRequestRequest(
                id: UUID(), tableNumber: 4,
                lines: [SettlementLineSelection(lineId: lineID, qty: 3)],
                requestedAt: Date()
            ))
            #expect(response.status == .ok)

            let dto = try harness.decode(PrintRequestDTO.self, from: response)
            #expect(dto.tableNumber == 4)
            #expect(dto.items.count == 1)
            #expect(dto.items[0].name == "Berner Würstel mit Gebäck")
            #expect(dto.items[0].qty == 3)
            #expect(dto.items[0].unitPriceCents == 620)
            #expect(dto.totalCents == 1860)

            // Der Blob muss dieselben Positionen wieder hergeben.
            let stored = try #require(try await PrintRequest.find(dto.id, on: harness.db))
            #expect(try stored.dto() == dto)
        }
    }

    @Test("Derselbe Druckauftrag zweimal erzeugt genau einen Datensatz")
    func idempotentPrintRequest() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let lineID = try await bookBerner(harness, token: token, table: 5, qty: 2)

            let request = CreatePrintRequestRequest(
                id: UUID(), tableNumber: 5,
                lines: [SettlementLineSelection(lineId: lineID, qty: 2)],
                requestedAt: Date()
            )
            let first = try await harness.send(.POST, APIRoute.printRequests, token: token, body: request)
            let second = try await harness.send(.POST, APIRoute.printRequests, token: token, body: request)

            #expect(first.status == .ok)
            #expect(second.status == .ok)
            #expect(try harness.decode(PrintRequestDTO.self, from: first) == harness.decode(PrintRequestDTO.self, from: second))
            #expect(try await PrintRequest.query(on: harness.db).count() == 1)
        }
    }

    @Test("Eine Teilmenge ergibt die anteilige Summe")
    func partialSelection() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let lineID = try await bookBerner(harness, token: token, table: 8, qty: 3)

            let response = try await harness.send(.POST, APIRoute.printRequests, token: token, body: CreatePrintRequestRequest(
                id: UUID(), tableNumber: 8,
                lines: [SettlementLineSelection(lineId: lineID, qty: 2)],
                requestedAt: Date()
            ))
            #expect(response.status == .ok)

            let dto = try harness.decode(PrintRequestDTO.self, from: response)
            #expect(dto.items[0].qty == 2)
            #expect(dto.totalCents == 1240)
        }
    }

    @Test("Der Druckauftrag lässt die Bestellzeilen unverändert")
    func linesStayUntouched() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let lineID = try await bookBerner(harness, token: token, table: 8, qty: 3)

            let response = try await harness.send(.POST, APIRoute.printRequests, token: token, body: CreatePrintRequestRequest(
                id: UUID(), tableNumber: 8,
                lines: [SettlementLineSelection(lineId: lineID, qty: 2)],
                requestedAt: Date()
            ))
            #expect(response.status == .ok)

            // Kein Split, keine Zuordnung: die Zeile steht danach wie vorher da.
            let lines = try await OrderLine.query(on: harness.db).all()
            #expect(lines.count == 1)
            let line = try #require(lines.first)
            #expect(line.id == lineID)
            #expect(line.qty == 3)
            #expect(line.settlementId == nil)
            #expect(line.voidedAt == nil)
            #expect(try await Settlement.query(on: harness.db).count() == 0)
        }
    }

    @Test("Eine bereits kassierte Zeile nennt das kassierende Gerät")
    func alreadySettledNamesDevice() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let schank = try await harness.login(deviceName: "Schank")
            let garten = try await harness.login(deviceName: "Gastgarten")
            let lineID = try await bookBerner(harness, token: schank.token, table: 7, qty: 1)

            let settled = try await harness.send(.POST, APIRoute.settlements, token: schank.token, body: CreateSettlementRequest(
                id: UUID(), tableNumber: 7,
                lines: [SettlementLineSelection(lineId: lineID, qty: 1)],
                amountCents: 620, paidAt: Date()
            ))
            #expect(settled.status == .ok)

            let response = try await harness.send(.POST, APIRoute.printRequests, token: garten.token, body: CreatePrintRequestRequest(
                id: UUID(), tableNumber: 7,
                lines: [SettlementLineSelection(lineId: lineID, qty: 1)],
                requestedAt: Date()
            ))
            #expect(response.status == .conflict)
            let conflict = try harness.decode(SettlementConflictDTO.self, from: response)
            #expect(conflict.reason == .alreadySettled)
            #expect(conflict.conflictingLineIds == [lineID])
            #expect(conflict.settledByDevice == "Schank")
            #expect(try await PrintRequest.query(on: harness.db).count() == 0)
        }
    }

    @Test("Stornierte Zeilen und zu große Mengen geben je den eigenen Grund")
    func otherConflictReasons() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token

            // Stornierte Zeile
            let voidedID = try await bookBerner(harness, token: token, table: 1, qty: 1)
            #expect(try await harness.send(.POST, APIRoute.voidLine(voidedID), token: token).status == .ok)
            let voided = try await harness.send(.POST, APIRoute.printRequests, token: token, body: CreatePrintRequestRequest(
                id: UUID(), tableNumber: 1,
                lines: [SettlementLineSelection(lineId: voidedID, qty: 1)],
                requestedAt: Date()
            ))
            #expect(voided.status == .conflict)
            #expect(try harness.decode(SettlementConflictDTO.self, from: voided).reason == .lineVoided)

            // Mehr drucken als gebucht
            let smallID = try await bookBerner(harness, token: token, table: 2, qty: 1)
            let exceeded = try await harness.send(.POST, APIRoute.printRequests, token: token, body: CreatePrintRequestRequest(
                id: UUID(), tableNumber: 2,
                lines: [SettlementLineSelection(lineId: smallID, qty: 5)],
                requestedAt: Date()
            ))
            #expect(exceeded.status == .conflict)
            let dto = try harness.decode(SettlementConflictDTO.self, from: exceeded)
            #expect(dto.reason == .quantityExceeded)
            #expect(dto.conflictingLineIds == [smallID])

            #expect(try await PrintRequest.query(on: harness.db).count() == 0)
        }
    }

    @Test("GET liefert nur Aufträge nach der übergebenen Sequenznummer")
    func sinceReturnsOnlyNewer() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token
            let firstLine = try await bookBerner(harness, token: token, table: 3, qty: 1)
            let secondLine = try await bookBerner(harness, token: token, table: 3, qty: 1)

            let first = try await harness.send(.POST, APIRoute.printRequests, token: token, body: CreatePrintRequestRequest(
                id: UUID(), tableNumber: 3,
                lines: [SettlementLineSelection(lineId: firstLine, qty: 1)],
                requestedAt: Date()
            ))
            #expect(first.status == .ok)
            let firstDTO = try harness.decode(PrintRequestDTO.self, from: first)

            let second = try await harness.send(.POST, APIRoute.printRequests, token: token, body: CreatePrintRequestRequest(
                id: UUID(), tableNumber: 3,
                lines: [SettlementLineSelection(lineId: secondLine, qty: 1)],
                requestedAt: Date()
            ))
            #expect(second.status == .ok)
            let secondDTO = try harness.decode(PrintRequestDTO.self, from: second)

            let all = try await harness.send(.GET, "\(APIRoute.printRequests)?since=0", token: token)
            #expect(all.status == .ok)
            #expect(try harness.decode([PrintRequestDTO].self, from: all).map(\.id) == [firstDTO.id, secondDTO.id])

            let newer = try await harness.send(.GET, "\(APIRoute.printRequests)?since=\(firstDTO.updatedSeq)", token: token)
            #expect(newer.status == .ok)
            #expect(try harness.decode([PrintRequestDTO].self, from: newer).map(\.id) == [secondDTO.id])

            let none = try await harness.send(.GET, "\(APIRoute.printRequests)?since=\(secondDTO.updatedSeq)", token: token)
            #expect(none.status == .ok)
            #expect(try harness.decode([PrintRequestDTO].self, from: none).isEmpty)
        }
    }

    @Test("Ohne Zeilen entsteht kein Druckauftrag")
    func emptySelectionRejected() async throws {
        try await withKassaApp { harness in
            try await harness.importPriceList()
            let token = try await harness.login().token

            let response = try await harness.send(.POST, APIRoute.printRequests, token: token, body: CreatePrintRequestRequest(
                id: UUID(), tableNumber: 3, lines: [], requestedAt: Date()
            ))
            #expect(response.status == .badRequest)
            #expect(try await PrintRequest.query(on: harness.db).count() == 0)
        }
    }
}

@Suite("Druckauftrag Tagesstatistik")
struct DayReportPrintRequestTests {
    /// Schickt bewusst ein `deviceId` mit, das im echten DTO gar nicht vorkommt —
    /// nur so lässt sich prüfen, dass der Server den Körper dabei ignoriert.
    private struct RequestWithDeviceId: Encodable {
        var id: UUID
        var businessDay: String
        var requestedAt: Date
        var deviceId: String
    }

    @Test("Derselbe Statistik-Auftrag zweimal erzeugt genau einen Datensatz")
    func idempotentDayReportPrintRequest() async throws {
        try await withKassaApp { harness in
            let token = try await harness.login().token
            let request = CreateDayReportPrintRequestRequest(
                id: UUID(), businessDay: "2026-09-05", requestedAt: Date()
            )

            let first = try await harness.send(.POST, APIRoute.dayReportPrintRequests, token: token, body: request)
            let second = try await harness.send(.POST, APIRoute.dayReportPrintRequests, token: token, body: request)

            #expect(first.status == .ok)
            #expect(second.status == .ok)

            // Gleiche updatedSeq: der zweite Versuch legt nicht nur keine zweite
            // Zeile an, er rückt den Auftrag auch nicht im Delta nach vorn.
            let firstDTO = try harness.decode(DayReportPrintRequestDTO.self, from: first)
            let secondDTO = try harness.decode(DayReportPrintRequestDTO.self, from: second)
            #expect(firstDTO == secondDTO)
            #expect(firstDTO.updatedSeq == secondDTO.updatedSeq)
            #expect(try await DayReportPrintRequest.query(on: harness.db).count() == 1)
        }
    }

    @Test("Ein unsinniger Betriebstag wird abgewiesen")
    func invalidBusinessDayRejected() async throws {
        try await withKassaApp { harness in
            let token = try await harness.login().token

            for unsinn in ["morgen", "", "2026-13-99-01"] {
                let response = try await harness.send(
                    .POST, APIRoute.dayReportPrintRequests, token: token,
                    body: CreateDayReportPrintRequestRequest(id: UUID(), businessDay: unsinn, requestedAt: Date())
                )
                #expect(response.status == .badRequest)
            }
            #expect(try await DayReportPrintRequest.query(on: harness.db).count() == 0)
        }
    }

    @Test("Die Geräte-ID kommt aus dem Token, nicht aus dem Körper")
    func deviceIdComesFromToken() async throws {
        try await withKassaApp { harness in
            let wirt = try await harness.login(deviceName: "iPad Wirt")

            let response = try await harness.send(
                .POST, APIRoute.dayReportPrintRequests, token: wirt.token,
                body: RequestWithDeviceId(
                    id: UUID(), businessDay: "2026-09-05", requestedAt: Date(),
                    deviceId: "fremdes-geraet"
                )
            )
            #expect(response.status == .ok)

            let dto = try harness.decode(DayReportPrintRequestDTO.self, from: response)
            #expect(dto.deviceId == wirt.deviceId)
            #expect(dto.deviceId != "fremdes-geraet")
        }
    }

    @Test("GET liefert nur Statistik-Aufträge nach der übergebenen Sequenznummer")
    func sinceReturnsOnlyNewer() async throws {
        try await withKassaApp { harness in
            let token = try await harness.login().token

            let first = try await harness.send(
                .POST, APIRoute.dayReportPrintRequests, token: token,
                body: CreateDayReportPrintRequestRequest(id: UUID(), businessDay: "2026-09-05", requestedAt: Date())
            )
            #expect(first.status == .ok)
            let firstDTO = try harness.decode(DayReportPrintRequestDTO.self, from: first)

            let second = try await harness.send(
                .POST, APIRoute.dayReportPrintRequests, token: token,
                body: CreateDayReportPrintRequestRequest(id: UUID(), businessDay: "2026-09-06", requestedAt: Date())
            )
            #expect(second.status == .ok)
            let secondDTO = try harness.decode(DayReportPrintRequestDTO.self, from: second)

            let all = try await harness.send(.GET, "\(APIRoute.dayReportPrintRequests)?since=0", token: token)
            #expect(all.status == .ok)
            #expect(try harness.decode([DayReportPrintRequestDTO].self, from: all).map(\.id) == [firstDTO.id, secondDTO.id])

            let newer = try await harness.send(.GET, "\(APIRoute.dayReportPrintRequests)?since=\(firstDTO.updatedSeq)", token: token)
            #expect(newer.status == .ok)
            #expect(try harness.decode([DayReportPrintRequestDTO].self, from: newer).map(\.id) == [secondDTO.id])

            let none = try await harness.send(.GET, "\(APIRoute.dayReportPrintRequests)?since=\(secondDTO.updatedSeq)", token: token)
            #expect(none.status == .ok)
            #expect(try harness.decode([DayReportPrintRequestDTO].self, from: none).isEmpty)
        }
    }

    /// Die Trennung der beiden Ströme ist die Zusicherung an einen alten
    /// Druckdienst am Küchen-Mac: Er pollt weiter `/print-requests` und darf dort
    /// nie eine Statistik-Zeile finden, die er als „Aufstellung Tisch 0" mit
    /// leeren Positionen ausdruckt.
    @Test("Ein Statistik-Auftrag taucht nicht bei den Aufstellungen auf")
    func streamsStaySeparate() async throws {
        try await withKassaApp { harness in
            let token = try await harness.login().token

            let created = try await harness.send(
                .POST, APIRoute.dayReportPrintRequests, token: token,
                body: CreateDayReportPrintRequestRequest(id: UUID(), businessDay: "2026-09-05", requestedAt: Date())
            )
            #expect(created.status == .ok)

            let aufstellungen = try await harness.send(.GET, "\(APIRoute.printRequests)?since=0", token: token)
            #expect(aufstellungen.status == .ok)
            #expect(try harness.decode([PrintRequestDTO].self, from: aufstellungen).isEmpty)
            #expect(try await PrintRequest.query(on: harness.db).count() == 0)
        }
    }
}
