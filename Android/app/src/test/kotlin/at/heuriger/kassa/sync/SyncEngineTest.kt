package at.heuriger.kassa.sync

import at.heuriger.kassa.data.ConnectionState
import at.heuriger.kassa.net.ApiException
import at.heuriger.kassa.wire.Money
import at.heuriger.kassa.wire.SettlementConflictDto
import at.heuriger.kassa.wire.SettlementLineSelection
import at.heuriger.kassa.wire.SyncPing
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Schleifen, Konflation und der Kassierkonflikt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncEngineTest {

    private var env: SyncEnvironment? = null

    @After
    fun tearDown() {
        env?.close()
        env = null
    }

    private suspend fun start(pollInterval: kotlin.time.Duration = 10.seconds): SyncEnvironment =
        SyncEnvironment.create(pollInterval = pollInterval).also { env = it }

    /**
     * Der Kern der Uebersetzung: iOS verwirft einen Sync-Wunsch, der waehrend
     * eines laufenden Syncs eintrifft. Der konflatierte Kanal merkt sich genau
     * einen und fuehrt ihn direkt danach aus — hundert Wuensche ergeben also
     * einen Nachlauf, nicht hundert.
     */
    @Test
    fun `hundert Sync-Wuensche ergeben hoechstens zwei Durchlaeufe`() = runBlocking {
        // Langes Intervall: was passiert, kommt von den Wuenschen, nicht vom Poll.
        val env = start(pollInterval = 10.seconds)

        // Den ersten Durchlauf anhalten, damit die hundert Wuensche garantiert
        // waehrend eines laufenden Syncs eintreffen.
        val gate = CompletableDeferred<Unit>()
        env.api.syncGate = gate

        env.engine.start()
        waitUntil { env.api.syncCallCount() >= 1 }

        repeat(100) { env.engine.requestSync() }

        env.api.syncGate = null
        gate.complete(Unit)

        waitUntil { env.api.syncCallCount() >= 2 }

        // Genug Luft, damit ein dritter Durchlauf auffallen wuerde.
        delay(300)
        assertEquals("hundert Wuensche, ein Nachlauf", 2, env.api.syncCallCount())
    }

    @Test
    fun `der Poll-Loop synchronisiert von selbst weiter`() = runBlocking {
        val env = start(pollInterval = 30.milliseconds)

        env.engine.start()
        waitUntil { env.api.syncCallCount() >= 4 }

        env.engine.stop()
        val afterStop = env.api.syncCallCount()
        delay(200)
        assertEquals("nach stop() laeuft nichts mehr", afterStop, env.api.syncCallCount())
    }

    @Test
    fun `ein WebSocket-Ping stoesst einen Sync an`() = runBlocking {
        val env = start(pollInterval = 10.seconds)

        env.engine.start()
        waitUntil { env.api.syncCallCount() >= 1 }

        env.api.pings.emit(SyncPing(seq = 7))
        waitUntil { env.api.syncCallCount() >= 2 }

        assertEquals(ConnectionState.CONNECTED, env.store.status.value.connection)
    }

    /**
     * Der heikelste Pfad: der Server lehnt das Kassieren ab. Lokal darf nichts
     * als kassiert stehen bleiben und schon gar nicht stillschweigend erneut
     * gebucht werden.
     */
    @Test
    fun `ein abgelehnter Kassiervorgang wird zurueckgenommen und neu geladen`() = runBlocking {
        val env = start()
        env.seedCatalog()
        env.book(table = 12, articleId = "b1", qty = 2)
        env.engine.syncNow()
        assertEquals(Money(880), env.openTotal(12))

        val line = env.store.lines(12).single()
        env.store.settle(
            tableNumber = 12,
            selections = listOf(SettlementLineSelection(line.id, qty = 2)),
            total = Money(880),
        ).job.join()
        assertEquals("lokal ist der Tisch erst einmal frei", Money.ZERO, env.openTotal(12))

        env.api.setNextSettlementError(
            ApiException.SettlementConflict(
                SettlementConflictDto(
                    conflictingLineIds = listOf(line.id),
                    settledByDevice = "Schank",
                    reason = SettlementConflictDto.Reason.ALREADY_SETTLED,
                )
            )
        )
        val syncsBefore = env.api.syncCallCount()
        env.engine.syncNow()

        assertEquals(
            "Tisch 12 wurde bereits von Schank kassiert.",
            env.store.status.value.conflictMessage,
        )
        assertEquals("die Zeilen sind wieder offen", Money(880), env.openTotal(12))
        assertNull("der lokale Vorgang ist weg", env.store.settlements().firstOrNull())
        assertEquals("der Befehl ist aus der Queue", 0, env.store.pendingCommands().size)
        assertEquals(
            "Vollabgleich plus der reguläre Abgleich",
            syncsBefore + 2,
            env.api.syncCallCount(),
        )
    }

    @Test
    fun `ein abgelehnter Storno-Befehl bleibt als fehlgeschlagen liegen`() = runBlocking {
        val env = start()
        env.seedCatalog()
        env.book(table = 13, articleId = "b1", qty = 1)
        env.engine.syncNow()

        // Eine Zeile, die der Server nicht kennt: das Storno kann nie gelingen.
        env.store.voidLine(java.util.UUID.randomUUID()).join()
        env.store.enqueue(
            at.heuriger.kassa.data.CommandKind.VOID_LINE,
            """{"lineId":"${java.util.UUID.randomUUID()}"}""",
        )

        env.engine.syncNow()

        assertEquals(1, env.store.failedPendingCount())
        val failed = env.store.pendingCommands().single { it.failedPermanently }
        assertEquals("Server-Fehler 404: unbekannte Zeile", failed.lastError)
        assertEquals("kein Konflikt-Dialog fuer ein Storno", null, env.store.status.value.conflictMessage)
    }

    @Test
    fun `bei Netzproblemen waechst das Poll-Intervall`() = runBlocking {
        val env = start(pollInterval = 20.milliseconds)
        env.api.setOffline(true)

        env.engine.start()
        waitUntil { env.store.status.value.connection == ConnectionState.OFFLINE }

        val afterFirstFailure = env.api.syncCallCount()
        // Backoff steht nach dem ersten Fehler auf 2x20 ms, waechst dann weiter.
        delay(300)
        val later = env.api.syncCallCount()

        assertTrue("es wird weiter versucht", later > afterFirstFailure)
        assertTrue(
            "aber nicht mehr im 20-ms-Takt (sonst waeren es ueber 15)",
            later - afterFirstFailure < 15,
        )
        assertEquals("Keine Verbindung zum Server.", env.store.status.value.lastErrorMessage)
    }

    @Test
    fun `nach erfolgreichem Sync ist der Fehler weg`() = runBlocking {
        val env = start()
        env.api.setOffline(true)
        env.engine.syncNow()
        assertNotNull(env.store.status.value.lastErrorMessage)
        assertEquals(ConnectionState.OFFLINE, env.store.status.value.connection)

        env.api.setOffline(false)
        env.engine.syncNow()

        assertNull(env.store.status.value.lastErrorMessage)
        assertEquals(ConnectionState.CONNECTED, env.store.status.value.connection)
        assertNotNull(env.store.status.value.lastSyncAt)
    }

    @Test
    fun `forceFullReload ersetzt den Spiegel und behaelt lokale Buchungen`() = runBlocking {
        val env = start()
        env.seedCatalog()
        env.book(table = 4, articleId = "b1", qty = 1)
        env.engine.syncNow()
        assertEquals(0, env.store.openPendingCount())

        // Offline nachgebucht: noch nicht bestaetigt.
        env.api.setOffline(true)
        env.book(table = 9, articleId = "b1", qty = 1)
        env.api.setOffline(false)

        env.engine.forceFullReload()

        assertEquals("der Serverstand ist da", Money(440), env.openTotal(4))
        assertEquals("die lokale Buchung ueberlebt", Money(440), env.openTotal(9))
        assertEquals(1, env.store.openPendingCount())
    }
}
