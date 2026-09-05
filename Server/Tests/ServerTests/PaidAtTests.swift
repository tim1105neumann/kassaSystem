import Foundation
import KassaShared
import Testing
@testable import KassaServer

@Suite("Zahlzeitpunkt")
struct PaidAtTests {
    private let now = Date(timeIntervalSince1970: 1_780_000_000)
    private let tolerance: TimeInterval = 24 * 60 * 60

    @Test("Normale Zahlung behält die Gerätezeit")
    func acceptsCurrentTime() {
        let claimed = now.addingTimeInterval(-30)
        #expect(plausiblePaidAt(claimed, now: now, tolerance: tolerance) == claimed)
    }

    @Test("Buchung aus der Offline-Queue behält ihren ursprünglichen Zeitpunkt")
    func acceptsOfflineDelay() {
        // Drei Stunden im Funkloch gehangen — zählt zu Recht noch zum
        // damaligen Betriebstag, nicht zum Zeitpunkt des Nachreichens.
        let claimed = now.addingTimeInterval(-3 * 3600)
        #expect(plausiblePaidAt(claimed, now: now, tolerance: tolerance) == claimed)
    }

    @Test("Uhr des Geräts geht Tage in die Zukunft -> Serverzeit gewinnt")
    func rejectsFuture() {
        let claimed = now.addingTimeInterval(3 * 24 * 3600)
        #expect(plausiblePaidAt(claimed, now: now, tolerance: tolerance) == now)
    }

    @Test("Uhr des Geräts steht Tage zurück -> Serverzeit gewinnt")
    func rejectsAncient() {
        let claimed = now.addingTimeInterval(-5 * 24 * 3600)
        #expect(plausiblePaidAt(claimed, now: now, tolerance: tolerance) == now)
    }

    @Test("Abweichung innerhalb der Toleranz bleibt unangetastet")
    func toleratesSmallSkew() {
        let claimed = now.addingTimeInterval(2 * 3600)
        #expect(plausiblePaidAt(claimed, now: now, tolerance: tolerance) == claimed)
    }

    @Test("Verworfene Gerätezeit landet im Betriebstag der Serverzeit")
    func fallbackLandsInServerBusinessDay() {
        // Gerät behauptet, es sei in einer Woche — der Tagesabschluss muss
        // die Zahlung trotzdem heute sehen.
        let claimed = now.addingTimeInterval(7 * 24 * 3600)
        let effective = plausiblePaidAt(claimed, now: now, tolerance: tolerance)
        #expect(
            BusinessDay.day(for: effective, cutoffHour: 6)
                == BusinessDay.day(for: now, cutoffHour: 6)
        )
    }
}
