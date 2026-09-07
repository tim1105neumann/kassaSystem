import Foundation
import Testing
@testable import KassaShared

@Suite("DTO-Codierung")
struct DTOCodingTests {
    @Test("Bestellzeile überlebt einen JSON-Rundlauf unverändert")
    func orderLineRoundTrip() throws {
        let original = OrderLineDTO(
            id: UUID(),
            tableNumber: 12,
            articleId: "abc123",
            nameSnapshot: "Käsekrainer mit Pommes",
            unitPriceCents: 920,
            qty: 3,
            createdAt: Date(timeIntervalSince1970: 1_780_000_000),
            deviceId: "device-a",
            updatedSeq: 42
        )
        let data = try KassaJSON.encoder.encode(original)
        let decoded = try KassaJSON.decoder.decode(OrderLineDTO.self, from: data)
        #expect(decoded == original)
        #expect(decoded.lineTotal.cents == 2760)
        #expect(decoded.isOpen)
    }

    @Test("Kassierte oder stornierte Zeile gilt nicht mehr als offen")
    func openState() {
        var line = OrderLineDTO(
            id: UUID(), tableNumber: 1, articleId: "a", nameSnapshot: "Bier 0,3 l",
            unitPriceCents: 380, qty: 1, createdAt: .now, deviceId: "d", updatedSeq: 1
        )
        #expect(line.isOpen)
        line.settlementId = UUID()
        #expect(!line.isOpen)

        var storniert = line
        storniert.settlementId = nil
        storniert.voidedAt = .now
        #expect(!storniert.isOpen)
    }

    @Test("Trinkgeld überlebt den Rundlauf und zählt nicht zum Umsatz")
    func settlementTipRoundTrip() throws {
        let original = SettlementDTO(
            id: UUID(),
            tableNumber: 4,
            totalCents: 2970,
            paidAt: Date(timeIntervalSince1970: 1_780_000_000),
            deviceId: "device-a",
            businessDay: "2026-09-05",
            updatedSeq: 7,
            tipCents: 130
        )
        let decoded = try KassaJSON.decoder.decode(
            SettlementDTO.self,
            from: try KassaJSON.encoder.encode(original)
        )
        #expect(decoded == original)
        #expect(decoded.total.cents == 2970)
        #expect(decoded.tip.cents == 130)
        #expect(decoded.grandTotal.cents == 3100)
    }

    /// Der Fall, der sonst still bricht: die Offline-Queue hält alte Payloads
    /// als rohes `Data`, die kennen `tipCents` noch nicht.
    @Test("Ein Kassiervorgang ohne tipCents dekodiert zu 0")
    func settlementRequestWithoutTipDecodes() throws {
        let json = """
        {
          "id": "6A3D2E64-1C1C-4C0E-9B2C-1F7E9E2A1B01",
          "tableNumber": 3,
          "lines": [{"lineId": "6A3D2E64-1C1C-4C0E-9B2C-1F7E9E2A1B02", "qty": 2}],
          "amountCents": 1240,
          "paidAt": "2026-09-05T21:00:00Z"
        }
        """
        let decoded = try KassaJSON.decoder.decode(
            CreateSettlementRequest.self,
            from: Data(json.utf8)
        )
        #expect(decoded.amountCents == 1240)
        #expect(decoded.tipCents == 0)
    }

    @Test("Ein Tagesbericht ohne tipCents dekodiert zu 0")
    func dayReportWithoutTipDecodes() throws {
        let json = """
        {
          "businessDay": "2026-09-05",
          "totalCents": 2970,
          "settlementCount": 1,
          "byCategory": [],
          "topArticles": [],
          "settlements": []
        }
        """
        let decoded = try KassaJSON.decoder.decode(DayReportDTO.self, from: Data(json.utf8))
        #expect(decoded.tipCents == 0)
        #expect(decoded.grandTotal.cents == 2970)
    }

    @Test("Druckauftrag-Anforderung überlebt einen JSON-Rundlauf unverändert")
    func createPrintRequestRoundTrip() throws {
        let lineId = UUID()
        let original = CreatePrintRequestRequest(
            id: UUID(),
            tableNumber: 9,
            lines: [SettlementLineSelection(lineId: lineId, qty: 2)],
            requestedAt: Date(timeIntervalSince1970: 1_780_000_000)
        )
        let data = try KassaJSON.encoder.encode(original)
        #expect(String(decoding: data, as: UTF8.self).contains("2026-05-28T20:26:40Z"))
        let decoded = try KassaJSON.decoder.decode(CreatePrintRequestRequest.self, from: data)
        #expect(decoded == original)
        #expect(decoded.lines == [SettlementLineSelection(lineId: lineId, qty: 2)])
    }

    @Test("Druckauftrag überlebt den Rundlauf samt verschachtelter Positionen")
    func printRequestRoundTrip() throws {
        let original = PrintRequestDTO(
            id: UUID(),
            tableNumber: 9,
            items: [
                PrintRequestDTO.Item(name: "Käsekrainer mit Pommes", qty: 2, unitPriceCents: 920),
                PrintRequestDTO.Item(name: "Bier 0,3 l", qty: 3, unitPriceCents: 380)
            ],
            totalCents: 2980,
            requestedAt: Date(timeIntervalSince1970: 1_780_000_000),
            deviceId: "device-a",
            updatedSeq: 13
        )
        let data = try KassaJSON.encoder.encode(original)
        #expect(String(decoding: data, as: UTF8.self).contains("2026-05-28T20:26:40Z"))
        let decoded = try KassaJSON.decoder.decode(PrintRequestDTO.self, from: data)
        #expect(decoded == original)
        #expect(decoded.items.count == 2)
        #expect(decoded.items[0].lineTotal.cents == 1840)
        #expect(decoded.items[1].lineTotal.cents == 1140)
        #expect(decoded.total.cents == 2980)
    }

    @Test("Konfliktantwort transportiert die betroffenen Zeilen")
    func conflictRoundTrip() throws {
        let id = UUID()
        let original = SettlementConflictDTO(
            conflictingLineIds: [id],
            settledByDevice: "iPhone Sepp",
            reason: .alreadySettled
        )
        let data = try KassaJSON.encoder.encode(original)
        let decoded = try KassaJSON.decoder.decode(SettlementConflictDTO.self, from: data)
        #expect(decoded.conflictingLineIds == [id])
        #expect(decoded.reason == .alreadySettled)
        #expect(decoded.settledByDevice == "iPhone Sepp")
    }
}
