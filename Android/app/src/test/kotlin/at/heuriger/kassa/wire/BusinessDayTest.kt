package at.heuriger.kassa.wire

import java.time.Instant
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spiegelt `Shared/Tests/KassaSharedTests/BusinessDayTests.swift`. */
class BusinessDayTest {

    private fun vienna(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(BusinessDay.TIME_ZONE)
            .toInstant()

    @Test
    fun `Buchung nach Mitternacht zaehlt noch zum Vortag`() {
        assertEquals("2026-09-04", BusinessDay.day(vienna(2026, 9, 5, 1, 30), cutoffHour = 6))
        assertEquals("2026-09-04", BusinessDay.day(vienna(2026, 9, 5, 5, 59), cutoffHour = 6))
    }

    @Test
    fun `ab dem Cutoff beginnt der neue Betriebstag`() {
        assertEquals("2026-09-05", BusinessDay.day(vienna(2026, 9, 5, 6, 0), cutoffHour = 6))
        assertEquals("2026-09-05", BusinessDay.day(vienna(2026, 9, 5, 19, 0), cutoffHour = 6))
        assertEquals("2026-09-05", BusinessDay.day(vienna(2026, 9, 5, 23, 59), cutoffHour = 6))
    }

    @Test
    fun `Zeitfenster deckt genau die Buchungen des Betriebstags ab`() {
        val range = BusinessDay.range("2026-09-05", cutoffHour = 6)
        assertNotNull(range)
        requireNotNull(range)

        val abendbuchung = vienna(2026, 9, 5, 20, 0)
        val nachMitternacht = vienna(2026, 9, 6, 2, 0)
        val zuFrueh = vienna(2026, 9, 5, 5, 0)

        assertTrue(abendbuchung in range)
        assertTrue(nachMitternacht in range)
        assertTrue(zuFrueh < range.start)
    }

    @Test
    fun `Cutoff 0 verhaelt sich wie ein normaler Kalendertag`() {
        assertEquals("2026-09-05", BusinessDay.day(vienna(2026, 9, 5, 1, 0), cutoffHour = 0))
    }

    /**
     * Die DST-Falle, gegen den echten Swift-Code gemessen. Der Cutoff wird von der
     * **absoluten** Zeit abgezogen. Wer stattdessen `LocalDateTime.minusHours`
     * nimmt, bekommt an diesen zwei Zeitpunkten den falschen Betriebstag — und nur
     * an diesen, ein harmloser DST-Zeitpunkt deckt den Fehler nicht auf.
     */
    @Test
    fun `Sommerzeitbeginn verschiebt den Betriebstag nicht`() {
        // 29.03.2026: Wien springt von 02:00 auf 03:00. Lokal 06:30 minus 6 Stunden
        // Wanduhr waere 00:30 am 29., absolut sind es aber nur 5 gelebte Stunden
        // bis Mitternacht — die Buchung gehoert noch zum 28.
        val fruehSommerzeit = vienna(2026, 3, 29, 6, 30)
        assertEquals(1_774_758_600L, fruehSommerzeit.epochSecond)
        assertEquals("2026-03-28", BusinessDay.day(fruehSommerzeit, cutoffHour = 6))

        assertEquals("2026-03-28", BusinessDay.day(vienna(2026, 3, 29, 3, 30), cutoffHour = 6))
        assertEquals("2026-03-29", BusinessDay.day(vienna(2026, 3, 29, 8, 0), cutoffHour = 6))
    }

    @Test
    fun `Winterzeitbeginn verschiebt den Betriebstag nicht`() {
        // 25.10.2026: Wien springt von 03:00 zurueck auf 02:00. Lokal 05:30 minus
        // 6 Stunden Wanduhr waere der 24., absolut sind es 7 gelebte Stunden —
        // die Buchung gehoert schon zum 25.
        val spaetWinterzeit = vienna(2026, 10, 25, 5, 30)
        assertEquals(1_792_902_600L, spaetWinterzeit.epochSecond)
        assertEquals("2026-10-25", BusinessDay.day(spaetWinterzeit, cutoffHour = 6))
    }

    /** Am Sommerzeitbeginn ist der Betriebstag nur 23 Stunden lang. */
    @Test
    fun `Zeitfenster folgt der Zeitumstellung kalendarisch`() {
        val range = requireNotNull(BusinessDay.range("2026-03-28", cutoffHour = 6))
        // Beide Grenzen gegen den Swift-Code gemessen.
        assertEquals(1_774_674_000L, range.start.epochSecond)
        assertEquals(1_774_756_800L, range.end.epochSecond)
        assertEquals(23 * 3600L, range.end.epochSecond - range.start.epochSecond)
    }

    @Test
    fun `Zeitfenster und Tageszuordnung stimmen ueberein`() {
        val range = requireNotNull(BusinessDay.range("2026-09-05", cutoffHour = 6))
        var probe = range.start
        while (probe < range.end) {
            assertEquals("2026-09-05", BusinessDay.day(probe, cutoffHour = 6))
            probe = probe.plusSeconds(1800)
        }
        assertEquals("2026-09-06", BusinessDay.day(range.end, cutoffHour = 6))
        assertEquals("2026-09-04", BusinessDay.day(range.start.minusSeconds(1), cutoffHour = 6))
    }

    @Test
    fun `unsinniger Betriebstag ergibt kein Zeitfenster`() {
        assertNull(BusinessDay.range("2026-09", cutoffHour = 6))
        assertNull(BusinessDay.range("kein-datum-hier", cutoffHour = 6))
        assertNull(BusinessDay.range("2026-13-05", cutoffHour = 6))
    }
}
