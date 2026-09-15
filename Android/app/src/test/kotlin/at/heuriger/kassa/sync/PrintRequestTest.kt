package at.heuriger.kassa.sync

import at.heuriger.kassa.domain.SettlementSelection
import at.heuriger.kassa.net.ApiException
import at.heuriger.kassa.wire.CreatePrintRequestRequest
import at.heuriger.kassa.wire.KassaClock
import at.heuriger.kassa.wire.SettlementLineSelection
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Der letzte offene Fall aus `App/Tests/CalculationTests.swift`:
 * `printRequestCarriesPartialSelection`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrintRequestTest {

    private lateinit var env: SyncEnvironment

    @Before
    fun setUp() = runBlocking { env = SyncEnvironment.create() }

    @After
    fun tearDown() = env.close()

    @Test
    fun `Aufstellung enthaelt genau die Teilbetrags-Auswahl`() = runBlocking {
        env.seedCatalog()
        env.book(table = 12, articleId = "a1", qty = 2)
        env.book(table = 12, articleId = "b1", qty = 3)
        env.engine.syncNow()

        val lines = env.store.lines(12)
        val krainer = lines.first { it.articleId == "a1" }
        val selection = SettlementSelection().set(1, krainer.id, max = 2)

        val request = CreatePrintRequestRequest(
            id = UUID.randomUUID(),
            tableNumber = 12,
            lines = selection.requestLines(lines),
            requestedAt = KassaClock.now(),
        )
        env.api.createPrintRequest(request)

        assertEquals(
            listOf(SettlementLineSelection(lineId = krainer.id, qty = 1)),
            env.api.serverPrintRequest(request.id)?.lines,
        )

        // Ein zweiter Druck braucht eine neue ID — dieselbe ergibt nur einen Zettel.
        env.api.createPrintRequest(request)
        assertEquals(2, env.api.printRequestCallCount())
        assertEquals(1, env.api.serverPrintRequestCount())
    }

    @Test
    fun `printOverview geht direkt ans Netz, nicht ueber die Queue`() = runBlocking {
        env.seedCatalog()
        env.book(table = 12, articleId = "b1", qty = 2)
        env.engine.syncNow()
        assertEquals(0, env.store.openPendingCount())

        val line = env.store.lines(12).single()
        val error = env.session.printOverview(
            tableNumber = 12,
            selections = listOf(SettlementLineSelection(line.id, qty = 1)),
        )

        assertNull("Erfolg meldet keine Nachricht", error)
        assertEquals(1, env.api.serverPrintRequestCount())
        assertEquals("der Druck landet nie in der Warteschlange", 0, env.store.openPendingCount())
    }

    @Test
    fun `jeder Druck bekommt eine neue ID`() = runBlocking {
        env.seedCatalog()
        env.book(table = 12, articleId = "b1", qty = 2)
        env.engine.syncNow()
        val line = env.store.lines(12).single()
        val selections = listOf(SettlementLineSelection(line.id, qty = 1))

        env.session.printOverview(12, selections)
        env.session.printOverview(12, selections)

        assertEquals(2, env.api.printRequestCallCount())
        assertEquals("zwei Knopfdruecke, zwei Zettel", 2, env.api.serverPrintRequestCount())
    }

    @Test
    fun `ein abgelehnter Druck liefert die deutsche Meldung`() = runBlocking {
        env.seedCatalog()
        env.api.setNextPrintRequestError(ApiException.Server(status = 503, reason = "Drucker offline"))

        val message = env.session.printOverview(12, emptyList())

        assertEquals("Server-Fehler 503: Drucker offline", message)
        assertEquals(0, env.api.serverPrintRequestCount())
    }
}
