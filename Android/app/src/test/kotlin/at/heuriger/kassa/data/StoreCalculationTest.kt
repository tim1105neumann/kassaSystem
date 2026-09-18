package at.heuriger.kassa.data

import at.heuriger.kassa.data.StoreEnvironment.Companion.line
import at.heuriger.kassa.domain.TableTotals
import at.heuriger.kassa.wire.Money
import at.heuriger.kassa.wire.SettlementLineSelection
import at.heuriger.kassa.wire.SyncResponse
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die Faelle aus `App/Tests/CalculationTests.swift`, die einen Store brauchen.
 * Die rein rechnerischen liegen in `at.heuriger.kassa.domain.CalculationTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StoreCalculationTest {

    private lateinit var env: StoreEnvironment

    @Before
    fun setUp() = runBlocking { env = StoreEnvironment.create() }

    @After
    fun tearDown() = env.close()

    @Test
    fun `Teilzahlung reduziert die offene Tischsumme um den kassierten Anteil`() = runBlocking {
        val bierId = UUID.randomUUID()
        env.store.merge(
            SyncResponse(listOf(line(bierId, qty = 3, seq = 1, table = 14)), emptyList(), maxSeq = 1)
        )
        assertEquals(Money(1_320), env.openTotal(14))

        env.store.settle(
            tableNumber = 14,
            selections = listOf(SettlementLineSelection(bierId, qty = 2)),
            total = Money(880),
        ).job.join()

        assertEquals(Money(440), env.openTotal(14))
        assertEquals(1, env.store.openPendingCount())
    }

    @Test
    fun `vollstaendiges Kassieren macht den Tisch frei`() = runBlocking {
        val bierId = UUID.randomUUID()
        env.store.merge(
            SyncResponse(listOf(line(bierId, qty = 2, seq = 1, table = 15)), emptyList(), maxSeq = 1)
        )

        env.store.settle(
            tableNumber = 15,
            selections = listOf(SettlementLineSelection(bierId, qty = 2)),
            total = Money(880),
        ).job.join()

        assertEquals(Money.ZERO, env.openTotal(15))
    }

    @Test
    fun `Menge verringern storniert die Zeile und bucht den Rest neu`() = runBlocking {
        env.seedCatalog()
        env.store.addLines(20, listOf(BookingItem(env.article("b1"), 3))).join()

        val line = env.store.lines(20).first()
        env.store.changeQty(line.id, newQty = 1).join()

        assertEquals(Money(440), env.openTotal(20))
        // Buchung + Storno + Restbuchung
        assertEquals(3, env.store.openPendingCount())
        assertEquals("die alte Zeile ist storniert", 2, env.store.lines(20).size)
    }

    @Test
    fun `die Restmenge bleibt an der Stelle der alten Zeile`() = runBlocking {
        env.seedCatalog()
        env.store.addLines(
            23,
            listOf(
                BookingItem(env.article("a1"), 3),
                BookingItem(env.article("b1"), 1),
            ),
        ).join()

        val krainer = TableTotals.openLines(env.store.lines(23)).first()
        env.store.changeQty(krainer.id, newQty = 2).join()

        val offen = TableTotals.openLines(env.store.lines(23))
        assertEquals(
            "die Restmenge ist ans Listenende gesprungen",
            listOf("Kaesekrainer", "Bier, Radler 0,5 l"),
            offen.map { it.nameSnapshot },
        )
        assertEquals(2, offen.first().qty)
    }

    @Test
    fun `eine Nachbestellung reiht sich hinten ein`() = runBlocking {
        env.seedCatalog()
        env.store.addLines(
            24,
            listOf(
                BookingItem(env.article("a1"), 1),
                BookingItem(env.article("b1"), 1),
            ),
        ).join()

        val krainer = TableTotals.openLines(env.store.lines(24)).first()
        env.store.changeQty(krainer.id, newQty = 2).join()

        assertEquals(
            listOf("Kaesekrainer", "Bier, Radler 0,5 l", "Kaesekrainer"),
            TableTotals.openLines(env.store.lines(24)).map { it.nameSnapshot },
        )
    }

    @Test
    fun `Menge erhoehen bucht nur die Differenz nach`() = runBlocking {
        env.seedCatalog()
        env.store.addLines(21, listOf(BookingItem(env.article("b1"), 1))).join()

        val line = env.store.lines(21).first()
        env.store.changeQty(line.id, newQty = 3).join()

        assertEquals(Money(1_320), env.openTotal(21))
        assertEquals("Buchung + Nachbuchung", 2, env.store.openPendingCount())
    }

    @Test
    fun `Kassieren traegt Betriebstag und Trinkgeld ein`() = runBlocking {
        val bierId = UUID.randomUUID()
        env.store.merge(
            SyncResponse(listOf(line(bierId, qty = 1, seq = 1, table = 16)), emptyList(), maxSeq = 1)
        )

        val handle = env.store.settle(
            tableNumber = 16,
            selections = listOf(SettlementLineSelection(bierId, qty = 1)),
            total = Money(440),
            tip = Money(60),
        )
        handle.job.join()

        val settlement = env.store.settlements().single()
        assertEquals(handle.settlementId, settlement.id)
        assertEquals(440, settlement.totalCents)
        assertEquals(60, settlement.tipCents)
        assertEquals(env.settings.deviceId, settlement.deviceId)
        // Betriebstag, nicht Kalendertag — der Cutoff steckt in BusinessDay.
        assertEquals(
            at.heuriger.kassa.wire.BusinessDay.day(settlement.paidAt, env.settings.cutoffHour),
            settlement.businessDay,
        )
    }

    @Test
    fun `eine stornierte Zeile laesst sich nicht noch einmal anfassen`() = runBlocking {
        env.seedCatalog()
        env.store.addLines(22, listOf(BookingItem(env.article("b1"), 2))).join()
        val line = env.store.lines(22).first()

        env.store.voidLine(line.id).join()
        assertEquals(Money.ZERO, env.openTotal(22))
        assertEquals(2, env.store.openPendingCount())

        env.store.voidLine(line.id).join()
        env.store.changeQty(line.id, newQty = 1).join()
        assertEquals("kein zweites Storno, keine Neubuchung", 2, env.store.openPendingCount())
    }

    @Test
    fun `verworfener Kassiervorgang oeffnet die Zeilen wieder`() = runBlocking {
        val bierId = UUID.randomUUID()
        env.store.merge(
            SyncResponse(listOf(line(bierId, qty = 2, seq = 1, table = 17)), emptyList(), maxSeq = 1)
        )

        val handle = env.store.settle(
            tableNumber = 17,
            selections = listOf(SettlementLineSelection(bierId, qty = 2)),
            total = Money(880),
        )
        handle.job.join()
        assertEquals(Money.ZERO, env.openTotal(17))
        assertNotNull(env.store.settlements().firstOrNull())

        val command = env.store.nextPendingCommand()!!
        assertEquals(CommandKind.SETTLE, command.kind)
        env.store.discardCommand(command.id)

        assertEquals("die Zeile ist wieder offen", Money(880), env.openTotal(17))
        assertNull("der lokale Vorgang ist weg", env.store.settlements().firstOrNull())
        assertEquals(0, env.store.openPendingCount())
    }
}
