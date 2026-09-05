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
