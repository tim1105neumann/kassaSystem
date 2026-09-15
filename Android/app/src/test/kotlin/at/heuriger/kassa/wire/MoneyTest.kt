package at.heuriger.kassa.wire

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spiegelt `Shared/Tests/KassaSharedTests/MoneyTests.swift`. */
class MoneyTest {

    @Test
    fun `formatiert oesterreichisch mit Komma und zwei Nachkommastellen`() {
        assertEquals("6,20 €", Money(620).formatted)
        assertEquals("0,00 €", Money(0).formatted)
        assertEquals("26,00 €", Money(2600).formatted)
        assertEquals("0,50 €", Money(50).formatted)
        assertEquals("1,70 €", Money(170).formatted)
        assertEquals("-1,50 €", Money(-150).formatted)
    }

    @Test
    fun `formattedPlain laesst das Waehrungszeichen weg`() {
        assertEquals("6,20", Money(620).formattedPlain)
        assertEquals("0,00", Money(0).formattedPlain)
        assertEquals("-1,50", Money(-150).formattedPlain)
    }

    @Test
    fun `parst Komma- und Punktschreibweise`() {
        assertEquals(620, Money.parse("6,20")?.cents)
        assertEquals(620, Money.parse("6.20")?.cents)
        assertEquals(600, Money.parse("6")?.cents)
        assertEquals(50, Money.parse(",50")?.cents)
        assertEquals(0, Money.parse("0,00")?.cents)
        assertEquals(440, Money.parse(" 4,40 € ")?.cents)
        assertEquals(650, Money.parse("6,5")?.cents)
    }

    @Test
    fun `weist Unsinn zurueck statt still zu raten`() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("6,205"))
        assertNull(Money.parse("1,2,3"))
    }

    @Test
    fun `rechnet ohne Rundungsfehler`() {
        // Der Klassiker, an dem Double scheitert: 0,10 + 0,20
        assertEquals(30, (Money(10) + Money(20)).cents)
        assertEquals(1860, (Money(620) * 3).cents)
        assertEquals(380, (Money(1000) - Money(620)).cents)
        assertEquals(Money.ZERO, Money(0))
    }

    @Test
    fun `vergleicht nach Cent`() {
        assertEquals(true, Money(10) < Money(20))
        assertEquals(true, Money(-10) < Money.ZERO)
    }

    @Test
    fun `codiert als reine Cent-Zahl, nie als Float`() {
        val json = KassaJson.instance.encodeToString(Money.serializer(), Money(620))
        assertEquals("620", json)
        assertEquals(620, KassaJson.instance.decodeFromString(Money.serializer(), json).cents)
    }

    @Test
    fun `codiert auch als Feld eines Objekts als nackte Zahl`() {
        // Absicherung gegen eine versehentliche Objekt-Kodierung "{\"cents\":620}".
        val serializer = ListSerializer(Money.serializer())
        assertEquals(
            "[620,-150]",
            KassaJson.instance.encodeToString(serializer, listOf(Money(620), Money(-150))),
        )
    }

    @Test
    fun `grosse Betraege laufen nicht ueber, sondern werden abgelehnt`() {
        // Kotlins Int ist 32 Bit, Swifts 64. Ohne Long-Zwischenrechnung ergaebe
        // "21474837" den negativen Betrag -2.147.483.596 statt einer Ablehnung.
        assertNull(Money.parse("21474837"))
        assertNull(Money.parse("99999999"))
        assertNull(Money.parse("99999999999"))
    }

    @Test
    fun `der groesste noch darstellbare Betrag geht durch`() {
        assertEquals(2_147_483_600, Money.parse("21474836")?.cents)
        assertNull(Money.parse("21474836,48"))
    }

    @Test
    fun `nur ASCII-Ziffern werden akzeptiert`() {
        // Swift lehnt arabisch-indische Ziffern ab; Kotlins isDigit() akzeptiert
        // jede Unicode-Ziffernkategorie und wuerde hier stillschweigend rechnen.
        assertNull(Money.parse("6,\u0662\u0665"))
        assertNull(Money.parse("\u0666,50"))
    }
}
