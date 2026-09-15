package at.heuriger.kassa.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import at.heuriger.kassa.data.db.KassaDatabase
import at.heuriger.kassa.domain.TableTotals
import at.heuriger.kassa.wire.ArticleDto
import at.heuriger.kassa.wire.Money
import at.heuriger.kassa.wire.OrderLineDto
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.robolectric.RuntimeEnvironment

/**
 * Pendant zu `App/Tests/TestSupport.swift`.
 *
 * Room braucht `android.*`, deshalb laeuft alles hier unter Robolectric mit
 * einer In-Memory-Datenbank.
 */
class StoreEnvironment private constructor(
    val db: KassaDatabase,
    val settings: SettingsStore,
    val store: KassaStore,
    private val scope: CoroutineScope,
    private val prefsFile: File,
) {

    /** Katalog lokal setzen, ohne einen Server zu befragen. */
    suspend fun seedCatalog() = store.replaceArticles(DEFAULT_ARTICLES)

    suspend fun article(id: String) = requireNotNull(store.article(id)) { "Artikel $id fehlt" }

    suspend fun openTotal(table: Int): Money = TableTotals.openTotal(store.lines(table))

    fun close() {
        scope.cancel()
        db.close()
        prefsFile.delete()
    }

    companion object {
        val DEFAULT_ARTICLES = listOf(
            ArticleDto(
                id = "a1", category = "Speisen", name = "Kaesekrainer",
                priceCents = 620, sortOrder = 1, active = true,
            ),
            ArticleDto(
                id = "b1", category = "Getraenke", name = "Bier, Radler 0,5 l",
                priceCents = 440, sortOrder = 2, active = true,
            ),
        )

        /**
         * Zeilen-DTO wie in `MergeTests.swift` — dieselben Zahlen, damit die
         * Erwartungswerte eins zu eins uebernommen werden koennen.
         */
        fun line(
            id: UUID,
            qty: Int,
            seq: Int,
            table: Int = 4,
            voidedAt: Instant? = null,
            settlementId: UUID? = null,
            note: String? = null,
        ): OrderLineDto = OrderLineDto(
            id = id,
            tableNumber = table,
            articleId = "b1",
            nameSnapshot = "Bier, Radler 0,5 l",
            unitPriceCents = 440,
            qty = qty,
            createdAt = Instant.ofEpochSecond(1_700_000_000),
            deviceId = "anderes-geraet",
            voidedAt = voidedAt,
            settlementId = settlementId,
            updatedSeq = seq,
            note = note,
        )

        suspend fun create(): StoreEnvironment {
            val context = RuntimeEnvironment.getApplication()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            val db = Room.inMemoryDatabaseBuilder(context, KassaDatabase::class.java).build()

            val prefsFile = File(context.filesDir, "settings-${UUID.randomUUID()}.preferences_pb")
            val dataStore: DataStore<Preferences> =
                PreferenceDataStoreFactory.create(scope = scope) { prefsFile }

            val settings = SettingsStore.create(dataStore, InMemoryTokenStore())
            val store = KassaStore(db, settings, scope)
            return StoreEnvironment(db, settings, store, scope, prefsFile)
        }
    }
}
