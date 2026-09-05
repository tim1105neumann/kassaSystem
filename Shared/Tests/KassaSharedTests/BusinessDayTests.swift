import Foundation
import Testing
@testable import KassaShared

@Suite("Betriebstag")
struct BusinessDayTests {
    private func vienna(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = BusinessDay.timeZone
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        components.hour = hour
        components.minute = minute
        return calendar.date(from: components)!
    }

    @Test("Buchung nach Mitternacht zählt noch zum Vortag")
    func afterMidnightBelongsToPreviousDay() {
        #expect(BusinessDay.day(for: vienna(2026, 9, 5, 1, 30), cutoffHour: 6) == "2026-09-04")
        #expect(BusinessDay.day(for: vienna(2026, 9, 5, 5, 59), cutoffHour: 6) == "2026-09-04")
    }

    @Test("Ab dem Cutoff beginnt der neue Betriebstag")
    func afterCutoffIsNewDay() {
        #expect(BusinessDay.day(for: vienna(2026, 9, 5, 6, 0), cutoffHour: 6) == "2026-09-05")
        #expect(BusinessDay.day(for: vienna(2026, 9, 5, 19, 0), cutoffHour: 6) == "2026-09-05")
        #expect(BusinessDay.day(for: vienna(2026, 9, 5, 23, 59), cutoffHour: 6) == "2026-09-05")
    }

    @Test("Zeitfenster deckt genau die Buchungen des Betriebstags ab")
    func rangeMatchesDayAssignment() throws {
        let range = try #require(BusinessDay.range(for: "2026-09-05", cutoffHour: 6))
        let abendbuchung = vienna(2026, 9, 5, 20, 0)
        let nachMitternacht = vienna(2026, 9, 6, 2, 0)
        let zuFrueh = vienna(2026, 9, 5, 5, 0)

        #expect(range.start <= abendbuchung && abendbuchung < range.end)
        #expect(range.start <= nachMitternacht && nachMitternacht < range.end)
        #expect(zuFrueh < range.start)
    }

    @Test("Cutoff 0 verhält sich wie ein normaler Kalendertag")
    func zeroCutoff() {
        #expect(BusinessDay.day(for: vienna(2026, 9, 5, 1, 0), cutoffHour: 0) == "2026-09-05")
    }
}
