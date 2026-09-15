package at.heuriger.kassa.data

import at.heuriger.kassa.wire.KassaClock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Offline-Queue: FIFO, Ueberspringen, Fehlerprotokoll. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueueTest {

    private lateinit var env: StoreEnvironment

    @Before
    fun setUp() = runBlocking { env = StoreEnvironment.create() }

    @After
    fun tearDown() = env.close()

    /**
     * Der eigentliche Grund fuer `order_index`: `createdAt` liegt auf Sekunden,
     * zwei Commands aus derselben Operation teilen sich den Zeitstempel. Nach
     * `createdAt` sortiert waere die Reihenfolge hier eine Muenzwurfsache.
     */
    @Test
    fun `Gleichstand im Zeitstempel aendert die FIFO-Reihenfolge nicht`() = runBlocking {
        val sameInstant = Instant.ofEpochSecond(1_700_000_000)
        repeat(20) { index ->
            env.store.enqueue(CommandKind.ADD_LINES, """{"n":$index}""", createdAt = sameInstant)
        }

        val payloads = env.store.pendingCommands().map { it.payload }
        assertEquals((0 until 20).map { """{"n":$it}""" }, payloads)
        assertTrue(
            "alle Commands teilen sich den Zeitstempel",
            env.store.pendingCommands().all { it.createdAt == sameInstant },
        )
        assertEquals("""{"n":0}""", env.store.nextPendingCommand()?.payload)
    }

    @Test
    fun `changeQty legt Storno vor Neubuchung in die Queue`() = runBlocking {
        env.seedCatalog()
        env.store.addLines(30, listOf(BookingItem(env.article("b1"), 3))).join()
        val line = env.store.lines(30).first()

        env.store.changeQty(line.id, newQty = 1).join()

        val kinds = env.store.pendingCommands().map { it.kind }
        assertEquals(
            listOf(CommandKind.ADD_LINES, CommandKind.VOID_LINE, CommandKind.ADD_LINES),
            kinds,
        )
        // Genau darauf kommt es an: der Server darf die Restbuchung nicht vor
        // dem Storno sehen, sonst steht die Menge kurzzeitig doppelt am Tisch.
        // Beide entstehen in derselben Sekunde, `createdAt` trennt sie also nicht.
        val commands = env.store.pendingCommands()
        assertEquals(commands[1].createdAt, commands[2].createdAt)
    }

    @Test
    fun `ein dauerhaft fehlgeschlagener Command blockiert die Queue nicht`() = runBlocking {
        env.store.enqueue(CommandKind.ADD_LINES, """{"n":1}""")
        env.store.enqueue(CommandKind.ADD_LINES, """{"n":2}""")

        val first = env.store.nextPendingCommand()!!
        assertEquals("""{"n":1}""", first.payload)

        env.store.recordFailure(first.id, message = "kaputt", permanent = true)

        val next = env.store.nextPendingCommand()!!
        assertEquals("der zweite Command kommt trotzdem dran", """{"n":2}""", next.payload)
        assertEquals(1, env.store.failedPendingCount())
        assertEquals(1, env.store.openPendingCount())

        val failed = env.store.pendingCommands().first { it.failedPermanently }
        assertEquals("kaputt", failed.lastError)
        assertEquals(1, failed.attemptCount)
    }

    @Test
    fun `ein voruebergehender Fehler zaehlt nur den Versuch hoch`() = runBlocking {
        env.store.enqueue(CommandKind.ADD_LINES, """{"n":1}""")
        val command = env.store.nextPendingCommand()!!

        env.store.recordFailure(command.id, message = "keine Verbindung", permanent = false)
        env.store.recordFailure(command.id, message = "keine Verbindung", permanent = false)

        val retried = env.store.pendingCommands().single()
        assertEquals(2, retried.attemptCount)
        assertTrue(!retried.failedPermanently)
        assertEquals("bleibt vorne in der FIFO", command.id, env.store.nextPendingCommand()?.id)
    }

    @Test
    fun `erledigter Command verschwindet aus der Queue`() = runBlocking {
        env.store.enqueue(CommandKind.ADD_LINES, """{"n":1}""")
        env.store.enqueue(CommandKind.ADD_LINES, """{"n":2}""")

        env.store.completeCommand(env.store.nextPendingCommand()!!.id)

        assertEquals(1, env.store.pendingCommands().size)
        assertEquals("""{"n":2}""", env.store.nextPendingCommand()?.payload)
    }

    @Test
    fun `ein unbekannter Command-Typ bringt die Queue nicht zum Absturz`() = runBlocking {
        env.db.pendingCommandDao().insert(
            at.heuriger.kassa.data.db.PendingCommandEntity(
                id = java.util.UUID.randomUUID(),
                kindRaw = "einZukuenftigerBefehl",
                payload = "{}",
                createdAt = KassaClock.now(),
            )
        )
        env.store.enqueue(CommandKind.ADD_LINES, """{"n":1}""")

        // `nextPendingCommand` liefert `null` statt zu werfen — wie Swifts
        // `guard let kind`. Die Queue steht dann zwar, aber kontrolliert: der
        // Eintrag laesst sich in den Einstellungen verwerfen.
        assertNull(env.store.nextPendingCommand())
        assertEquals("er taucht in der Anzeige nicht auf", 1, env.store.pendingCommands().size)
    }

    @Test
    fun `wipeAll raeumt Spiegel und Warteschlange`() = runBlocking {
        env.seedCatalog()
        env.store.addLines(31, listOf(BookingItem(env.article("b1"), 1))).join()
        assertNotNull(env.store.nextPendingCommand())

        env.store.wipeAll()

        assertTrue(env.store.allLines().isEmpty())
        assertTrue(env.store.settlements().isEmpty())
        assertTrue(env.store.pendingCommands().isEmpty())
        assertTrue(env.store.articles().isEmpty())
    }
}
