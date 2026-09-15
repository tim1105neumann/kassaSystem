package at.heuriger.kassa.data

import at.heuriger.kassa.wire.ArticleDto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `replaceArticles` — Spiegel von `LocalStore.replaceArticles(_:)`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogTest {

    private lateinit var env: StoreEnvironment

    @Before
    fun setUp() = runBlocking { env = StoreEnvironment.create() }

    @After
    fun tearDown() = env.close()

    @Test
    fun `ein fehlender Artikel wird stillgelegt, nicht geloescht`() = runBlocking {
        env.seedCatalog()
        assertEquals(2, env.store.articles().size)

        // Neuer CSV-Import ohne das Bier.
        env.store.replaceArticles(StoreEnvironment.DEFAULT_ARTICLES.filter { it.id == "a1" })

        val articles = env.store.articles().associateBy { it.id }
        assertEquals("der Artikel bleibt fuer alte Zeilen erhalten", 2, articles.size)
        assertTrue(articles.getValue("a1").active)
        assertFalse(articles.getValue("b1").active)
    }

    @Test
    fun `ein Preisupdate schlaegt auf den Katalog durch, nicht auf alte Zeilen`() = runBlocking {
        env.seedCatalog()
        env.store.addLines(3, listOf(BookingItem(env.article("b1"), 2))).join()

        env.store.replaceArticles(
            listOf(
                ArticleDto(
                    id = "b1", category = "Getraenke", name = "Bier, Radler 0,5 l",
                    priceCents = 480, sortOrder = 2, active = true,
                )
            )
        )

        assertEquals(480, env.article("b1").priceCents)
        assertEquals(
            "der Preis ist bei der Buchung eingefroren",
            440,
            env.store.lines(3).first().unitPriceCents,
        )
    }

    @Test
    fun `ein wieder aufgenommener Artikel wird reaktiviert`() = runBlocking {
        env.seedCatalog()
        env.store.replaceArticles(emptyList())
        assertTrue(env.store.articles().none { it.active })

        env.store.replaceArticles(StoreEnvironment.DEFAULT_ARTICLES)
        assertTrue(env.store.articles().all { it.active })
    }
}
