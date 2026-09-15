package at.heuriger.kassa.data

import at.heuriger.kassa.wire.CreateOrderLinesRequest
import at.heuriger.kassa.wire.KassaJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die Notiz pro Position: sie muss sowohl lokal stehen als auch in der
 * Warteschlange liegen, und sie muss die Mengenaenderung ueberleben.
 *
 * `changeQty` ist der gefaehrliche Pfad: der Vertrag kennt nur „ganze Zeile
 * stornieren", die Restmenge wird also neu gebucht. Ohne die mitreisende Notiz
 * verliert ein Tipp auf Minus den Sonderwunsch still — und genau dieser Pfad
 * erzeugt den Stornobon in der Kueche.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteTest {

    private val json = KassaJson.instance
    private lateinit var env: StoreEnvironment

    @Before
    fun setUp() = runBlocking { env = StoreEnvironment.create() }

    @After
    fun tearDown() = env.close()

    /** Die Notizen aller `addLines`-Commands in Warteschlangenreihenfolge. */
    private suspend fun queuedNotes(): List<String?> = env.store.pendingCommands()
        .filter { it.kind == CommandKind.ADD_LINES }
        .flatMap { json.decodeFromString(CreateOrderLinesRequest.serializer(), it.payload).lines }
        .map { it.note }

    @Test
    fun `gebuchte Notiz steht lokal und in der Warteschlange`() = runBlocking {
        env.seedCatalog()

        env.store.addLines(
            21,
            listOf(BookingItem(env.article("a1"), qty = 2, note = "ohne Senf")),
        ).join()

        assertEquals("ohne Senf", env.store.lines(21).single().note)
        assertEquals(listOf("ohne Senf"), queuedNotes())
    }

    @Test
    fun `Buchung ohne Notiz bleibt null und schickt das Feld nicht mit`() = runBlocking {
        env.seedCatalog()

        env.store.addLines(22, listOf(BookingItem(env.article("a1"), qty = 1))).join()

        assertNull(env.store.lines(22).single().note)
        // `explicitNulls = false`: ein `"note": null` waere fuer den Server ein
        // Feld, das es im Swift-Vertrag so nie gibt.
        val payload = env.store.pendingCommands().single().payload
        assertFalse("Ohne Notiz darf der Schluessel fehlen: $payload", payload.contains("note"))
    }

    @Test
    fun `Notiz uebersteht das Erhoehen der Menge`() = runBlocking {
        env.seedCatalog()
        env.store.addLines(
            23,
            listOf(BookingItem(env.article("a1"), qty = 1, note = "extra Senf")),
        ).join()
        val line = env.store.lines(23).single()

        env.store.changeQty(line.id, newQty = 3).join()

        // Erhoehen ist eine zusaetzliche Buchung: die alte Zeile bleibt offen.
        val notes = env.store.lines(23).filter { it.isOpen }.map { it.note }
        assertEquals(listOf("extra Senf", "extra Senf"), notes)
        assertEquals(listOf("extra Senf", "extra Senf"), queuedNotes())
    }

    @Test
    fun `Notiz uebersteht das Verringern der Menge`() = runBlocking {
        env.seedCatalog()
        env.store.addLines(
            24,
            listOf(BookingItem(env.article("a1"), qty = 3, note = "ohne Zwiebel")),
        ).join()
        val line = env.store.lines(24).single()

        env.store.changeQty(line.id, newQty = 1).join()

        val remaining = env.store.lines(24).single { it.isOpen }
        assertEquals(1, remaining.qty)
        assertEquals("ohne Zwiebel", remaining.note)
        assertEquals(listOf("ohne Zwiebel", "ohne Zwiebel"), queuedNotes())
    }
}
