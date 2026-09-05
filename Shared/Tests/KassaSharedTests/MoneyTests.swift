import Foundation
import Testing
@testable import KassaShared

@Suite("Money")
struct MoneyTests {
    @Test("Formatiert österreichisch mit Komma und zwei Nachkommastellen")
    func formatting() {
        #expect(Money(cents: 620).formatted == "6,20 €")
        #expect(Money(cents: 0).formatted == "0,00 €")
        #expect(Money(cents: 2600).formatted == "26,00 €")
        #expect(Money(cents: 50).formatted == "0,50 €")
        #expect(Money(cents: 170).formatted == "1,70 €")
        #expect(Money(cents: -150).formatted == "-1,50 €")
    }

    @Test("Parst Komma- und Punktschreibweise")
    func parsing() {
        #expect(Money(parsing: "6,20")?.cents == 620)
        #expect(Money(parsing: "6.20")?.cents == 620)
        #expect(Money(parsing: "6")?.cents == 600)
        #expect(Money(parsing: ",50")?.cents == 50)
        #expect(Money(parsing: "0,00")?.cents == 0)
        #expect(Money(parsing: " 4,40 € ")?.cents == 440)
        #expect(Money(parsing: "6,5")?.cents == 650)
    }

    @Test("Weist Unsinn zurück statt still zu raten")
    func parsingRejectsGarbage() {
        #expect(Money(parsing: "") == nil)
        #expect(Money(parsing: "abc") == nil)
        #expect(Money(parsing: "6,205") == nil)
        #expect(Money(parsing: "1,2,3") == nil)
    }

    @Test("Rechnet ohne Rundungsfehler")
    func arithmetic() {
        // Der Klassiker, an dem Double scheitert: 0,10 + 0,20
        #expect((Money(cents: 10) + Money(cents: 20)).cents == 30)
        #expect((Money(cents: 620) * 3).cents == 1860)
        #expect((Money(cents: 1000) - Money(cents: 620)).cents == 380)
    }

    @Test("Codiert als reine Cent-Zahl, nie als Float")
    func coding() throws {
        let data = try KassaJSON.encoder.encode(Money(cents: 620))
        #expect(String(decoding: data, as: UTF8.self) == "620")
        let decoded = try KassaJSON.decoder.decode(Money.self, from: data)
        #expect(decoded.cents == 620)
    }
}
