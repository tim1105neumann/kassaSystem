package at.heuriger.kassa.sync

import at.heuriger.kassa.net.ApiException
import at.heuriger.kassa.wire.Money
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spiegelt `App/Tests/OfflineQueueTests.swift`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineQueueTest {

    private lateinit var env: SyncEnvironment

    @Before
    fun setUp() = runBlocking { env = SyncEnvironment.create() }

    @After
    fun tearDown() = env.close()

    @Test
    fun `offline gebuchte Zeilen erscheinen sofort in der Tischsumme`() = runBlocking {
        env.api.setOffline(true)
        env.seedCatalog()

        env.book(table = 5, articleId = "b1", qty = 2)

        assertEquals(Money(880), env.openTotal(5))
        assertEquals(1, env.store.openPendingCount())
        assertEquals(0, env.api.serverLineCount())
    }

    @Test
    fun `Netz kommt zurueck, Queue wird abgearbeitet, ohne Doppelbuchung`() = runBlocking {
        env.seedCatalog()
        env.book(table = 5, articleId = "b1", qty = 2)

        // Der Server wendet den Command an, die Antwort geht aber verloren.
        env.api.setLoseNextResponse(true)
        env.engine.syncNow()

        assertEquals("Command muss in der Queue bleiben", 1, env.store.openPendingCount())
        assertEquals(1, env.api.serverLineCount())

        // Zweiter Versuch: derselbe Command wird nochmal gesendet.
        env.engine.syncNow()

        assertEquals(2, env.api.orderLineCallCount())
        assertEquals("idempotent auf der Client-UUID", 1, env.api.serverLineCount())
        assertEquals(0, env.store.openPendingCount())
        assertEquals(1, env.store.lines(5).size)
        assertEquals(Money(880), env.openTotal(5))
    }

    /**
     * Die Notiz muss den ganzen Weg gehen: sie haengt an der Bestellzeile, weil
     * nicht die App den Kuechenbon ausloest, sondern der Druckdienst, der `/sync`
     * pollt. Kommt sie am Server nicht an, druckt die Kueche den Sonderwunsch nie.
     */
    @Test
    fun `eine offline gebuchte Notiz erreicht den Server`() = runBlocking {
        env.api.setOffline(true)
        env.seedCatalog()
        env.book(table = 6, articleId = "a1", qty = 1, note = "ohne Senf")

        env.api.setOffline(false)
        env.engine.syncNow()

        val lineId = env.store.lines(6).single().id
        assertEquals("ohne Senf", env.api.serverLine(lineId)?.note)
        // Und sie ueberlebt den Weg zurueck: der Serverstand gewinnt beim Merge
        // vollstaendig, wuerde die Notiz also ueberschreiben, wenn sie fehlte.
        assertEquals("ohne Senf", env.store.lines(6).single().note)
        assertEquals(0, env.store.openPendingCount())
    }

    @Test
    fun `ein 4xx-Command blockiert die Queue nicht dauerhaft`() = runBlocking {
        env.seedCatalog()
        env.book(table = 2, articleId = "b1", qty = 1)
        env.book(table = 2, articleId = "a1", qty = 1)
        assertEquals(2, env.store.openPendingCount())

        env.api.setNextOrderLinesError(ApiException.Server(status = 400, reason = "kaputt"))
        env.engine.syncNow()

        assertEquals(
            "der defekte Command bleibt als fehlgeschlagen liegen",
            1,
            env.store.failedPendingCount(),
        )
        assertEquals("der zweite Command wurde trotzdem gesendet", 0, env.store.openPendingCount())
        assertEquals(1, env.api.serverLineCount())

        val failed = env.store.pendingCommands().first { it.failedPermanently }
        assertNotNull(failed.lastError)
        assertEquals("Server-Fehler 400: kaputt", failed.lastError)
    }

    @Test
    fun `verworfener Command verschwindet aus der Queue`() = runBlocking {
        env.seedCatalog()
        env.book(table = 2, articleId = "b1", qty = 1)

        env.api.setNextOrderLinesError(ApiException.Server(status = 400, reason = "kaputt"))
        env.engine.syncNow()

        val failed = env.store.pendingCommands().first { it.failedPermanently }
        env.store.discardCommand(failed.id)

        assertTrue(env.store.pendingCommands().isEmpty())
    }

    @Test
    fun `ein Transportfehler pausiert die Queue und haelt die Reihenfolge`() = runBlocking {
        env.seedCatalog()
        env.book(table = 3, articleId = "b1", qty = 1)
        env.book(table = 3, articleId = "a1", qty = 1)

        env.api.setOffline(true)
        env.engine.syncNow()

        // Nichts ist raus, beide Commands stehen noch — in ihrer Reihenfolge.
        assertEquals(0, env.api.serverLineCount())
        assertEquals(2, env.store.openPendingCount())
        assertEquals(0, env.store.failedPendingCount())
        assertEquals(1, env.store.pendingCommands().first().attemptCount)
        assertEquals("Keine Verbindung zum Server.", env.store.status.value.lastErrorMessage)

        env.api.setOffline(false)
        env.engine.syncNow()

        assertEquals(2, env.api.serverLineCount())
        assertEquals(0, env.store.openPendingCount())
    }
}
