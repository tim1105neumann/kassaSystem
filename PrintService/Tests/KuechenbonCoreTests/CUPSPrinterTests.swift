import Foundation
import Testing
@testable import KuechenbonCore

@Suite("Druckerzustand")
struct CUPSPrinterTests {

    @Test("Eine laufende Warteschlange gilt nicht als gestoppt")
    func laufendeQueue() {
        let ausgabe = "device-uri=usb://Printer/Printer-80 printer-state=3 printer-state-reasons=none"
        #expect(CUPSPrinter.gestoppt(lpoptionsAusgabe: ausgabe) == nil)
    }

    @Test("Eine angehaltene Warteschlange nennt den Grund")
    func angehalteneQueue() {
        let ausgabe = "device-uri=usb://Printer/Printer-80 printer-state=5 printer-state-reasons=paused"
        #expect(CUPSPrinter.gestoppt(lpoptionsAusgabe: ausgabe) == "paused")
    }

    @Test("Ohne angegebenen Grund bleibt die Meldung trotzdem verständlich")
    func ohneGrund() {
        #expect(CUPSPrinter.gestoppt(lpoptionsAusgabe: "printer-state=5") == "Grund unbekannt")
    }

    @Test("Eine leere Antwort gilt als laufend, damit ein Ausfall von lpoptions nicht den Druck blockiert")
    func leereAusgabe() {
        #expect(CUPSPrinter.gestoppt(lpoptionsAusgabe: "") == nil)
    }
}
