package at.heuriger.kassa.wire

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Der Heuriger hat nach Mitternacht offen — ein Kalendertag wuerde den
 * Tagesabschluss mitten im Betrieb durchschneiden. Alles vor `cutoffHour`
 * zaehlt deshalb noch zum Vortag.
 */
object BusinessDay {

    val TIME_ZONE: ZoneId = ZoneId.of("Europe/Vienna")

    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd")

    /**
     * Wichtig: `cutoffHour` wird von der **absoluten** Zeit abgezogen und erst
     * danach in Wien auf Jahr/Monat/Tag reduziert — genau wie Swifts
     * `calendar.date(byAdding: .hour, value: -cutoffHour, to: date)`.
     * Mit `LocalDateTime.minusHours` wiche das Ergebnis an der Sommerzeitgrenze ab.
     */
    fun day(instant: Instant, cutoffHour: Int): String {
        val shifted = instant.minus(Duration.ofHours(cutoffHour.toLong()))
        return shifted.atZone(TIME_ZONE).toLocalDate().format(DAY_FORMAT)
    }

    /** Zeitfenster `[start, end)` eines Betriebstags — fuer Report-Abfragen. */
    fun range(day: String, cutoffHour: Int): Range? {
        val parts = day.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return null
        val date = try {
            LocalDate.of(parts[0], parts[1], parts[2])
        } catch (_: java.time.DateTimeException) {
            return null
        }

        // Lokale Cutoff-Zeit in Wien aufloesen (wie Swifts DateComponents mit
        // hour = cutoffHour) und kalendarisch — nicht um 24 Stunden — weiterzaehlen:
        // an der Zeitumstellung ist ein Betriebstag 23 bzw. 25 Stunden lang.
        val start = date.atTime(cutoffHour, 0).atZone(TIME_ZONE)
        val end = start.plusDays(1)
        return Range(start.toInstant(), end.toInstant())
    }

    /** Halboffenes Intervall: `start` gehoert dazu, `end` nicht mehr. */
    data class Range(val start: Instant, val end: Instant) {
        operator fun contains(instant: Instant): Boolean = instant >= start && instant < end
    }
}
