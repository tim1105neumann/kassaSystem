import Foundation
import Testing
@testable import KuechenbonCore

@Suite("CP437-Kodierung")
struct CP437Tests {

    @Test("Käsekrainer wird an der Umlautstelle zu 0x84")
    func umlautWirdCP437() {
        let bytes = [UInt8](CP437.encode("Käsekrainer", asciiFallback: false))
        #expect(bytes.count == 11)
        #expect(bytes[0] == 0x4B)  // K
        #expect(bytes[1] == 0x84)  // ä
        #expect(bytes[2] == 0x73)  // s
    }

    @Test("Mit ASCII-Fallback steht dort Kaesekrainer")
    func fallbackTransliteriert() {
        let bytes = CP437.encode("Käsekrainer", asciiFallback: true)
        #expect(String(data: bytes, encoding: .ascii) == "Kaesekrainer")
    }

    @Test("Alle Umlaute und das scharfe S haben die richtigen CP437-Werte")
    func alleUmlaute() {
        let bytes = [UInt8](CP437.encode("äöüÄÖÜß", asciiFallback: false))
        #expect(bytes == [0x84, 0x94, 0x81, 0x8E, 0x99, 0x9A, 0xE1])

        let ascii = CP437.encode("äöüÄÖÜß", asciiFallback: true)
        #expect(String(data: ascii, encoding: .ascii) == "aeoeueAeOeUess")
    }

    @Test("Druckbares ASCII, Apostroph und Schrägstrich gehen unverändert durch")
    func asciiUnveraendert() {
        let text = "Grillhendl 1/2 'Extra'"
        let bytes = CP437.encode(text, asciiFallback: false)
        #expect(String(data: bytes, encoding: .ascii) == text)
    }

    @Test("Zeilenumbrüche bleiben erhalten, alles andere Unbekannte wird zum Fragezeichen")
    func unbekanntesWirdFragezeichen() {
        let bytes = [UInt8](CP437.encode("A\nB\u{2013}C😀", asciiFallback: false))
        #expect(bytes == [0x41, 0x0A, 0x42, 0x3F, 0x43, 0x3F])
    }
}
