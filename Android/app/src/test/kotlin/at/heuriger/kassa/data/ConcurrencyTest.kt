package at.heuriger.kassa.data

import at.heuriger.kassa.data.StoreEnvironment.Companion.line
import at.heuriger.kassa.wire.Money
import at.heuriger.kassa.wire.SyncResponse
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
 * Auf iOS serialisiert der MainActor jeden Zugriff. Hier tut das ein Mutex —
 * und der muss halten, wenn die UI schneller tippt als SQLite schreibt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConcurrencyTest {

    private lateinit var env: StoreEnvironment

    @Before
    fun setUp() = runBlocking { env = StoreEnvironment.create() }

    @After
    fun tearDown() = env.close()

    @Test
    fun `200 gleichzeitige Buchungen und 50 Merges verlieren nichts`() = runBlocking {
        env.seedCatalog()
        val bier = env.article("b1")

        val bookings = 200
        val merges = 50

        coroutineScope {
            val jobs = buildList {
                repeat(bookings) {
                    add(async(Dispatchers.Default) {
                        env.store.addLines(7, listOf(BookingItem(bier, 1))).join()
                    })
                }
                repeat(merges) { index ->
                    add(async(Dispatchers.Default) {
                        env.store.merge(
                            SyncResponse(
                                lines = listOf(
                                    line(UUID.randomUUID(), qty = 1, seq = index + 1, table = 99)
                                ),
                                settlements = emptyList(),
                                maxSeq = index + 1,
                            )
                        )
                    })
                }
            }
            jobs.awaitAll()
        }

        assertEquals("keine verlorene Buchung", bookings, env.store.lines(7).size)
        assertEquals(Money(440 * bookings), env.openTotal(7))
        assertEquals("jeder Merge ist angekommen", merges, env.store.lines(99).size)
        assertEquals("pro Buchung genau ein Command", bookings, env.store.openPendingCount())
        assertEquals(merges, env.settings.maxSeq)
        assertNull("kein Schreibfehler unterwegs", env.store.status.value.lastErrorMessage)
    }

    @Test
    fun `gleichzeitiges Einreihen bleibt lueckenlos und geordnet`() = runBlocking {
        val commands = 150

        coroutineScope {
            repeat(commands) { index ->
                launch(Dispatchers.Default) {
                    env.store.enqueue(CommandKind.ADD_LINES, """{"n":$index}""")
                }
            }
        }

        val queued = env.store.pendingCommands()
        assertEquals(commands, queued.size)
        assertEquals("keine doppelte ID", commands, queued.map { it.id }.toSet().size)
        assertEquals(
            "jeder Payload genau einmal",
            (0 until commands).map { """{"n":$it}""" }.toSet(),
            queued.map { it.payload }.toSet(),
        )
    }

    @Test
    fun `gleichzeitiges Kassieren desselben Tisches kassiert jede Zeile nur einmal`() = runBlocking {
        val ids = List(20) { UUID.randomUUID() }
        env.store.merge(
            SyncResponse(
                lines = ids.map { line(it, qty = 1, seq = 1, table = 12) },
                settlements = emptyList(),
                maxSeq = 1,
            )
        )

        coroutineScope {
            ids.map { id ->
                async(Dispatchers.Default) {
                    env.store.settle(
                        tableNumber = 12,
                        selections = listOf(
                            at.heuriger.kassa.wire.SettlementLineSelection(id, qty = 1)
                        ),
                        total = Money(440),
                    ).job.join()
                }
            }.awaitAll()
        }

        assertEquals(Money.ZERO, env.openTotal(12))
        assertEquals(20, env.store.settlements().size)
        assertEquals(
            "jede Zeile haengt an genau einem Vorgang",
            20,
            env.store.allLines().mapNotNull { it.settlementId }.toSet().size,
        )
    }
}
