import Foundation

/// Der Bondrucker kennt kein UTF-8. Codepage 437 ist die Voreinstellung
/// praktisch jedes ESC/POS-Geräts; welche der Munbyn wirklich beherrscht,
/// zeigt erst der erste Probebon — dafür gibt es den ASCII-Fallback.
public enum CP437 {

    private static let umlauts: [Character: UInt8] = [
        "ä": 0x84, "ö": 0x94, "ü": 0x81,
        "Ä": 0x8E, "Ö": 0x99, "Ü": 0x9A,
        "ß": 0xE1
    ]

    private static let transliterated: [Character: String] = [
        "ä": "ae", "ö": "oe", "ü": "ue",
        "Ä": "Ae", "Ö": "Oe", "Ü": "Ue",
        "ß": "ss"
    ]

    public static func encode(_ text: String, asciiFallback: Bool) -> Data {
        var bytes: [UInt8] = []
        for character in text {
            if asciiFallback, let replacement = transliterated[character] {
                bytes.append(contentsOf: Array(replacement.utf8))
                continue
            }
            if !asciiFallback, let byte = umlauts[character] {
                bytes.append(byte)
                continue
            }
            // Zeilenumbruch muss durch, sonst wäre der ganze Bon eine Zeile.
            if character == "\n" {
                bytes.append(0x0A)
                continue
            }
            if let ascii = character.asciiValue, (0x20...0x7E).contains(ascii) {
                bytes.append(ascii)
            } else {
                bytes.append(0x3F) // '?'
            }
        }
        return Data(bytes)
    }
}
