package at.heuriger.kassa.data

import at.heuriger.kassa.data.StoreEnvironment.Companion.line
import at.heuriger.kassa.wire.Money
import at.heuriger.kassa.wire.SyncResponse
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spiegelt `App/Tests/MergeTests.swift`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MergeTest {

    private lateinit var env: StoreEnvironment

    @Before
    fun setUp() = runBlocking { env = StoreEnvironment.create() }

    @After
    fun tearDown() = env.close()

    @Test
    fun `Serverzeile mit hoeherem updatedSeq ueberschreibt die lokale, maxSeq waechst mit`() =
        runBlocking {
            val id = UUID.randomUUID()

            env.store.merge(SyncResponse(listOf(line(id, qty = 1, seq = 5)), emptyList(), maxSeq = 5))
            assertEquals(5, env.settings.maxSeq)
            assertEquals(Money(440), env.openTotal(4))

            env.store.merge(SyncResponse(listOf(line(id, qty = 3, seq = 9)), emptyList(), maxSeq = 9))
            assertEquals(9, env.settings.maxSeq)
            assertEquals(1, env.store.lines(4).size)
            assertEquals(Money(1_320), env.openTotal(4))
        }

    @Test
    fun `eine aeltere Serverzeile ueberschreibt den neueren lokalen Stand nicht`() = runBlocking {
        val id = UUID.randomUUID()

        env.store.merge(SyncResponse(listOf(line(id, qty = 3, seq = 9)), emptyList(), maxSeq = 9))
        env.store.merge(SyncResponse(listOf(line(id, qty = 1, seq = 4)), emptyList(), maxSeq = 9))

        assertEquals(Money(1_320), env.openTotal(4))
        assertEquals(9, env.settings.maxSeq)
    }

    @Test
    fun `isFullReload ersetzt den Spiegel, behaelt aber Commands und lokale Buchungen`() =
        runBlocking {
            env.seedCatalog()

            val veraltet = UUID.randomUUID()
            env.store.merge(
                SyncResponse(listOf(line(veraltet, qty = 2, seq = 3)), emptyList(), maxSeq = 3)
            )
            assertEquals(1, env.store.lines(4).size)

            // Offline gebucht, noch nicht bestaetigt.
            env.store.addLines(9, listOf(BookingItem(env.article("b1"), 1))).join()
            assertEquals(1, env.store.openPendingCount())

            val neu = UUID.randomUUID()
            env.store.merge(
                SyncResponse(
                    lines = listOf(line(neu, qty = 1, seq = 20, table = 6)),
                    settlements = emptyList(),
                    maxSeq = 20,
                    isFullReload = true,
                )
            )

            assertTrue("alter Serverstand ist weg", env.store.lines(4).isEmpty())
            assertEquals("neuer Serverstand ist da", Money(440), env.openTotal(6))
            assertEquals("lokale Buchung ueberlebt", Money(440), env.openTotal(9))
            assertEquals("PendingCommand wurde nicht weggeworfen", 1, env.store.openPendingCount())
            assertEquals(20, env.settings.maxSeq)
        }

    @Test
    fun `stornierte und kassierte Zeilen zaehlen nicht zur offenen Tischsumme`() = runBlocking {
        val voided = line(UUID.randomUUID(), qty = 2, seq = 1, table = 8,
            voidedAt = Instant.ofEpochSecond(1_700_000_100))
        val settled = line(UUID.randomUUID(), qty = 5, seq = 2, table = 8,
            settlementId = UUID.randomUUID())
        val open = line(UUID.randomUUID(), qty = 3, seq = 3, table = 8)

        env.store.merge(SyncResponse(listOf(voided, settled, open), emptyList(), maxSeq = 3))

        assertEquals(3, env.store.lines(8).size)
        assertEquals(Money(1_320), env.openTotal(8))
    }
}
