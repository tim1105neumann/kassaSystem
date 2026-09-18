package at.heuriger.kassa.domain

import at.heuriger.kassa.wire.Money
import at.heuriger.kassa.wire.SettlementLineSelection
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spiegelt `App/Tests/CalculationTests.swift`. */
class CalculationTest {

    private val now: Instant = Instant.parse("2026-09-12T18:00:00Z")

    private fun line(
        id: UUID,
        name: String,
        unitPriceCents: Int,
        qty: Int,
        createdAt: Instant = now,
        voidedAt: Instant? = null,
        settlementId: UUID? = null,
        sortKey: Long? = null,
    ) = OrderLine(
        id = id,
        tableNumber = 12,
        articleId = "a1",
        nameSnapshot = name,
        unitPriceCents = unitPriceCents,
        qty = qty,
        createdAt = createdAt,
        deviceId = "d",
        voidedAt = voidedAt,
        settlementId = settlementId,
        updatedSeq = 1,
        sortKey = sortKey,
    )

    // MARK: - Rueckgeld

    @Test
    fun `20,00 € auf 13,60 € ergibt 6,40 € Rueckgeld`() {
        assertEquals(
            ChangeResult.Success(Money(640)),
            ChangeCalculator.change(total = Money(1_360), given = Money(2_000)),
        )
    }

    @Test
    fun `passend gezahlt ergibt kein Rueckgeld`() {
        assertEquals(
            ChangeResult.Success(Money.ZERO),
            ChangeCalculator.change(total = Money(1_360), given = Money(1_360)),
        )
    }

    @Test
    fun `zu wenig Geld ist ein Fehler, kein negatives Rueckgeld`() {
        val result = ChangeCalculator.change(total = Money(1_360), given = Money(1_000))
        assertEquals(ChangeResult.NotEnough(Money(360)), result)
        assertTrue("Es duerfte kein Rueckgeld geben, geliefert wurde $result",
            result !is ChangeResult.Success)
    }

    @Test
    fun `Schnellwahl enthaelt den passenden Betrag und deckende Scheine`() {
        val amounts = ChangeCalculator.quickAmounts(Money(1_360))
        assertTrue(amounts.contains(Money(1_360)))
        assertTrue(amounts.contains(Money(2_000)))
        assertTrue(amounts.all { it.cents >= 1_360 })
        assertEquals(amounts.sortedBy { it.cents }, amounts)
        assertTrue("hoechstens sechs Vorschlaege", amounts.size <= 6)
    }

    @Test
    fun `ohne Betrag gibt es keine Schnellwahl`() {
        assertTrue(ChangeCalculator.quickAmounts(Money.ZERO).isEmpty())
    }

    // MARK: - Trinkgeld

    @Test
    fun `31,00 € auf 29,70 € ergibt 1,30 € Trinkgeld`() {
        assertEquals(
            TipResult.Success(Money(130)),
            TipCalculator.tip(total = Money(2_970), paid = Money(3_100)),
        )
    }

    @Test
    fun `genau die Rechnung ergibt kein Trinkgeld`() {
        assertEquals(
            TipResult.Success(Money.ZERO),
            TipCalculator.tip(total = Money(2_970), paid = Money(2_970)),
        )
    }

    @Test
    fun `weniger als die Rechnung ist ein Fehler, kein negatives Trinkgeld`() {
        val result = TipCalculator.tip(total = Money(2_970), paid = Money(2_900))
        assertEquals(TipResult.BelowTotal(Money(70)), result)
        assertTrue("Es duerfte kein Trinkgeld geben, geliefert wurde $result",
            result !is TipResult.Success)
    }

    @Test
    fun `Aufrundungsvorschlaege fuer 29,70 €`() {
        assertEquals(
            listOf(2_970, 3_000, 3_100, 3_200, 3_500).map { Money(it) },
            TipCalculator.quickTotals(Money(2_970)),
        )
    }

    @Test
    fun `ohne Betrag gibt es nichts aufzurunden`() {
        assertTrue(TipCalculator.quickTotals(Money.ZERO).isEmpty())
    }

    // MARK: - Teilzahlung

    @Test
    fun `Zwischensumme der ausgewaehlten Mengen stimmt`() {
        val krainerId = UUID.randomUUID()
        val bierId = UUID.randomUUID()
        val lines = listOf(
            line(krainerId, "Kaesekrainer", unitPriceCents = 620, qty = 2),
            line(bierId, "Bier", unitPriceCents = 440, qty = 3),
        )

        var selection = SettlementSelection()
        selection = selection.set(1, krainerId, max = 2)
        selection = selection.set(2, bierId, max = 3)

        assertEquals(Money(1_500), selection.subtotal(lines))
        assertEquals(2, selection.requestLines(lines).size)

        // Ueber die vorhandene Menge hinaus laesst sich nichts auswaehlen.
        selection = selection.set(9, bierId, max = 3)
        assertEquals(3, selection.qty(bierId))
        assertEquals(Money(1_940), selection.subtotal(lines))

        assertEquals(Money(2_560), SettlementSelection.all(lines).subtotal(lines))
    }

    @Test
    fun `Auswahl auf null entfernt die Zeile`() {
        val bierId = UUID.randomUUID()
        val lines = listOf(line(bierId, "Bier", unitPriceCents = 440, qty = 3))

        val selection = SettlementSelection().set(2, bierId, max = 3).set(0, bierId, max = 3)

        assertTrue(selection.isEmpty)
        assertEquals(0, selection.qty(bierId))
        assertEquals(Money.ZERO, selection.subtotal(lines))
        assertTrue(selection.requestLines(lines).isEmpty())
    }

    @Test
    fun `negative Menge wird auf null geklemmt`() {
        val bierId = UUID.randomUUID()
        assertEquals(0, SettlementSelection().set(-5, bierId, max = 3).qty(bierId))
    }

    @Test
    fun `Nullmengen kommen gar nicht erst in die Auswahl`() {
        val bierId = UUID.randomUUID()
        assertTrue(SettlementSelection(mapOf(bierId to 0)).isEmpty)
    }

    @Test
    fun `Aufstellung enthaelt genau die Teilbetrags-Auswahl`() {
        val krainerId = UUID.randomUUID()
        val bierId = UUID.randomUUID()
        val lines = listOf(
            line(krainerId, "Kaesekrainer", unitPriceCents = 620, qty = 2),
            line(bierId, "Bier", unitPriceCents = 440, qty = 3),
        )

        val selection = SettlementSelection().set(1, krainerId, max = 2)

        assertEquals(
            listOf(SettlementLineSelection(lineId = krainerId, qty = 1)),
            selection.requestLines(lines),
        )
    }

    @Test
    fun `kassierte und stornierte Zeilen zaehlen nicht mit`() {
        val offen = UUID.randomUUID()
        val storniert = UUID.randomUUID()
        val kassiert = UUID.randomUUID()
        val lines = listOf(
            line(offen, "Bier", unitPriceCents = 440, qty = 2),
            line(storniert, "Bier", unitPriceCents = 440, qty = 2, voidedAt = now),
            line(kassiert, "Bier", unitPriceCents = 440, qty = 2, settlementId = UUID.randomUUID()),
        )

        val selection = SettlementSelection.all(lines)
        assertEquals(Money(880), selection.subtotal(lines))
        assertEquals(1, selection.requestLines(lines).size)
    }

    // MARK: - Tischsummen

    @Test
    fun `offene Tischsumme laesst Storniertes und Kassiertes weg`() {
        val frueh = now.minusSeconds(600)
        val lines = listOf(
            line(UUID.randomUUID(), "Bier", unitPriceCents = 440, qty = 3),
            line(UUID.randomUUID(), "Kaesekrainer", unitPriceCents = 620, qty = 1, createdAt = frueh),
            line(UUID.randomUUID(), "Bier", unitPriceCents = 440, qty = 1, voidedAt = now),
            line(UUID.randomUUID(), "Bier", unitPriceCents = 440, qty = 1,
                settlementId = UUID.randomUUID()),
        )

        assertEquals(Money(1_940), TableTotals.openTotal(lines))
        assertEquals(2, TableTotals.openLines(lines).size)
        assertEquals("Kaesekrainer", TableTotals.openLines(lines).first().nameSnapshot)
        assertEquals(frueh, TableTotals.openedAt(lines))
    }

    @Test
    fun `eine Buchung behaelt die Reihenfolge des Warenkorbs`() {
        // Alle drei in derselben Sekunde gebucht: ohne eigenen Schluessel
        // entschiede die Datenbank, und die entscheidet jedes Mal neu.
        val lines = listOf(
            line(UUID.randomUUID(), "Most", 380, 1, sortKey = 1_700_000_000_002),
            line(UUID.randomUUID(), "Kaesekrainer", 620, 3, sortKey = 1_700_000_000_000),
            line(UUID.randomUUID(), "Schnitzel", 1_490, 2, sortKey = 1_700_000_000_001),
        )

        assertEquals(
            listOf("Kaesekrainer", "Schnitzel", "Most"),
            TableTotals.openLines(lines).map { it.nameSnapshot },
        )
    }

    @Test
    fun `die neu gebuchte Restmenge bleibt an ihrem Platz`() {
        // Was `changeQty` beim Verringern hinterlaesst: die alte Zeile
        // storniert, der Rest frisch gebucht — also mit neuem `createdAt`, aber
        // mit dem geerbten Platz.
        val rest = line(
            UUID.randomUUID(), "Kaesekrainer", 620, 2,
            createdAt = now.plusSeconds(600), sortKey = 1_700_000_000_000,
        )
        val lines = listOf(
            line(UUID.randomUUID(), "Kaesekrainer", 620, 3,
                voidedAt = now.plusSeconds(600), sortKey = 1_700_000_000_000),
            rest,
            line(UUID.randomUUID(), "Schnitzel", 1_490, 2, sortKey = 1_700_000_000_001),
            line(UUID.randomUUID(), "Most", 380, 1, sortKey = 1_700_000_000_002),
        )

        assertEquals(
            listOf("Kaesekrainer", "Schnitzel", "Most"),
            TableTotals.openLines(lines).map { it.nameSnapshot },
        )
    }

    @Test
    fun `Zeilen ohne eigenen Schluessel gehen nach dem Buchungszeitpunkt`() {
        // Zeilen von einem anderen Geraet: der Vertrag traegt keinen
        // Sortierschluessel, sie muessen sich trotzdem eindeutig einreihen.
        val frueh = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val spaet = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val fremd = listOf(
            line(spaet, "Most", 380, 1),
            line(frueh, "Kaesekrainer", 620, 1, createdAt = now.minusSeconds(60)),
        )
        assertEquals(listOf(frueh, spaet), TableTotals.openLines(fremd).map { it.id })

        // Gleichstand im Zeitstempel: die ID entscheidet, und zwar immer gleich.
        val gleichzeitig = listOf(
            line(spaet, "Most", 380, 1),
            line(frueh, "Kaesekrainer", 620, 1),
        )
        assertEquals(listOf(frueh, spaet), TableTotals.openLines(gleichzeitig).map { it.id })
        assertEquals(
            TableTotals.openLines(gleichzeitig).map { it.id },
            TableTotals.openLines(gleichzeitig.reversed()).map { it.id },
        )
    }

    @Test
    fun `ohne offene Zeilen gibt es keinen Startzeitpunkt`() {
        val lines = listOf(line(UUID.randomUUID(), "Bier", 440, 1, voidedAt = now))
        assertEquals(Money.ZERO, TableTotals.openTotal(lines))
        assertEquals(null, TableTotals.openedAt(lines))
    }

    // MARK: - Kategorien

    @Test
    fun `Kategorie-Rohwerte bleiben ohne Umlaut`() {
        assertEquals("Speisen", ArticleCategory.SPEISEN.rawValue)
        assertEquals("Getraenke", ArticleCategory.GETRAENKE.rawValue)
        assertEquals(ArticleCategory.GETRAENKE, ArticleCategory.fromRawValue("Getraenke"))
        assertEquals(null, ArticleCategory.fromRawValue("Getränke"))
    }
}
