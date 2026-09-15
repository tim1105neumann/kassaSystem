package at.heuriger.kassa.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import at.heuriger.kassa.data.BookingItem
import at.heuriger.kassa.data.InMemoryTokenStore
import at.heuriger.kassa.data.KassaStore
import at.heuriger.kassa.data.SettingsStore
import at.heuriger.kassa.data.db.KassaDatabase
import at.heuriger.kassa.domain.TableTotals
import at.heuriger.kassa.wire.Money
import java.io.File
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/** Pendant zu `App/Tests/TestSupport.swift`, erweitert um Engine und Session. */
class SyncEnvironment private constructor(
    val db: KassaDatabase,
    val settings: SettingsStore,
    val store: KassaStore,
    val api: FakeKassaApi,
    val engine: SyncEngine,
    val session: SessionController,
    private val scope: CoroutineScope,
    private val prefsFile: File,
) {

    /** Katalog lokal setzen, ohne den Fake-Server zu befragen. */
    suspend fun seedCatalog() = store.replaceArticles(FakeKassaApi.DEFAULT_ARTICLES)

    suspend fun article(id: String) = requireNotNull(store.article(id)) { "Artikel $id fehlt" }

    suspend fun book(table: Int, articleId: String, qty: Int, note: String? = null) =
        store.addLines(table, listOf(BookingItem(article(articleId), qty, note))).join()

    suspend fun openTotal(table: Int): Money = TableTotals.openTotal(store.lines(table))

    fun close() {
        engine.stop()
        scope.cancel()
        db.close()
        prefsFile.delete()
    }

    companion object {
        suspend fun create(
            pollInterval: Duration = 10.seconds,
            reconnectDelay: Duration = 5.seconds,
        ): SyncEnvironment {
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            val db = Room.inMemoryDatabaseBuilder(context, KassaDatabase::class.java).build()

            val prefsFile = File(context.filesDir, "sync-${UUID.randomUUID()}.preferences_pb")
            val dataStore: DataStore<Preferences> =
                PreferenceDataStoreFactory.create(scope = scope) { prefsFile }

            val settings = SettingsStore.create(dataStore, InMemoryTokenStore())
            val store = KassaStore(db, settings, scope)
            val api = FakeKassaApi()

            val engine = SyncEngine(
                store = store,
                settings = settings,
                apiProvider = { api },
                scope = scope,
                pollInterval = pollInterval,
                reconnectDelay = reconnectDelay,
            )
            val session = SessionController(
                store = store,
                settings = settings,
                engine = engine,
                configureApi = { _, _ -> },
                apiProvider = { api },
            )

            return SyncEnvironment(db, settings, store, api, engine, session, scope, prefsFile)
        }
    }
}

/**
 * Wartet in echter Zeit auf eine Bedingung.
 *
 * Die Engine-Schleifen liessen sich nicht mit `runTest`s virtueller Zeit
 * beobachten: Room fuehrt jede Transaktion auf seinen eigenen Executors aus,
 * `advanceUntilIdle()` kaeme also zurueck, waehrend die Datenbankarbeit noch
 * laeuft. Statt dessen kurze Intervalle plus dieses Warten — kein Test wartet
 * laenger, als er muss, und keiner schlaeft zehn Sekunden.
 */
suspend fun waitUntil(timeoutMillis: Long = 3_000, condition: suspend () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return
        delay(5)
    }
    throw AssertionError("Bedingung nicht innerhalb von ${timeoutMillis} ms erfuellt")
}
