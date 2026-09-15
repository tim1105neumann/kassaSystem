package at.heuriger.kassa

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import at.heuriger.kassa.data.KassaStore
import at.heuriger.kassa.data.KeystoreTokenStore
import at.heuriger.kassa.data.SettingsStore
import at.heuriger.kassa.data.TokenStore
import at.heuriger.kassa.data.db.KassaDatabase
import at.heuriger.kassa.net.KassaApi
import at.heuriger.kassa.net.KtorKassaApi
import at.heuriger.kassa.sync.SessionController
import at.heuriger.kassa.sync.SyncEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * Liefert den Server-Client fuer eine Konfiguration.
 *
 * Die Ktor-Umsetzung gibt immer dieselbe Instanz zurueck und richtet sie nur
 * neu aus — ein neuer `HttpClient` samt Verbindungspool pro Adressaenderung
 * waere Verschwendung. Ein Test setzt hier sein Fake ein und ignoriert die
 * Parameter.
 */
fun interface KassaApiFactory {
    fun api(baseUrl: String?, token: String?): KassaApi
}

/** Der echte Client. Eine Instanz, deren Ziel sich aendern darf. */
class KtorApiFactory : KassaApiFactory {
    private val client = KtorKassaApi()

    override fun api(baseUrl: String?, token: String?): KassaApi {
        client.configure(baseUrl, token)
        return client
    }
}

/**
 * Kompositionswurzel — das Pendant zu `AppModel.init` auf iOS.
 *
 * Bewusst kein Hilt: der Graph hat acht Knoten und genau eine Instanz pro Typ.
 * Manuelle Konstruktorinjektion ist hier kuerzer als die Annotationen, die sie
 * ersetzen wuerden, und im Test ohne Testrunner-Magie aufzubauen.
 */
class AppContainer private constructor(
    val appScope: CoroutineScope,
    val db: KassaDatabase,
    val tokenStore: TokenStore,
    val settings: SettingsStore,
    val store: KassaStore,
    private val apiFactory: KassaApiFactory,
) {
    @Volatile
    var api: KassaApi = apiFactory.api(null, null)
        private set

    val engine: SyncEngine = SyncEngine(
        store = store,
        settings = settings,
        apiProvider = { api },
        scope = appScope,
    )

    val session: SessionController = SessionController(
        store = store,
        settings = settings,
        engine = engine,
        configureApi = { baseUrl, token -> api = apiFactory.api(baseUrl, token) },
        apiProvider = { api },
    )

    companion object {
        /**
         * `suspend`, weil [SettingsStore] seinen Spiegel einmal vom Datentraeger
         * lesen muss, bevor irgendwer `maxSeq` oder `deviceId` synchron abfragt.
         */
        suspend fun create(
            context: Context,
            appScope: CoroutineScope,
            apiFactory: KassaApiFactory = KtorApiFactory(),
            /**
             * Ueberschreibbar, weil es den AndroidKeyStore unter Robolectric
             * nicht gibt — ein Test setzt hier seinen Speicher ein.
             */
            tokenStore: TokenStore = KeystoreTokenStore(context.applicationContext),
            /**
             * Ueberschreibbar, weil Robolectric die echte [KassaApplication]
             * hochfaehrt: deren Container haelt die Standarddatei bereits offen,
             * und DataStore laesst keine zweite Instanz darauf zu.
             */
            preferencesFileName: String = "kassa.preferences_pb",
        ): AppContainer {
            val application = context.applicationContext
            val db = KassaDatabase.open(application)

            val dataStore: DataStore<Preferences> =
                PreferenceDataStoreFactory.create(scope = appScope) {
                    File(application.filesDir, preferencesFileName)
                }

            val settings = SettingsStore.create(dataStore, tokenStore)
            val store = KassaStore(db, settings, appScope)

            return AppContainer(appScope, db, tokenStore, settings, store, apiFactory)
        }
    }
}
