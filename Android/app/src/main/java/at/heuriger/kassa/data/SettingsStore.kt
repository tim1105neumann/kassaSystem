package at.heuriger.kassa.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import at.heuriger.kassa.wire.LoginResponse
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet

/**
 * Server-URL, Geraetename und der Sync-Zeiger. Spiegel von `AppSettings` aus
 * `App/Sources/Sync/AppSettings.swift`.
 *
 * Persistiert wird in DataStore, gelesen wird aus einem In-Memory-Spiegel:
 * `maxSeq` und `deviceId` muessen im Merge **synchron** verfuegbar sein, und ein
 * `dataStore.data.first()` mitten in einer Room-Transaktion waere genau die Art
 * versteckter Suspendierung, die spaeter sporadisch haengt.
 *
 * Nichts Sensibles — das Token liegt im [TokenStore].
 */
class SettingsStore private constructor(
    private val dataStore: DataStore<Preferences>,
    private val tokenStore: TokenStore,
    initial: Settings,
) {
    private val _settings = MutableStateFlow(initial)
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _token = MutableStateFlow(tokenStore.read())
    val token: StateFlow<String?> = _token.asStateFlow()

    val serverUrl: String get() = _settings.value.serverUrl
    val deviceName: String get() = _settings.value.deviceName
    val deviceId: String get() = _settings.value.deviceId
    val maxSeq: Int get() = _settings.value.maxSeq
    val tableCount: Int get() = _settings.value.tableCount
    val cutoffHour: Int get() = _settings.value.cutoffHour
    val catalogVersion: Int get() = _settings.value.catalogVersion

    val isLoggedIn: Boolean get() = _token.value != null && serverUrl.isNotBlank()

    /**
     * Dasselbe als Flow. Der synchrone Getter oben weckt die Komposition nicht
     * auf — die Wurzel-Navigation muss aber auf An- und Abmeldung reagieren.
     */
    val isLoggedInFlow: Flow<Boolean> =
        combine(_token, _settings) { token, settings ->
            token != null && settings.serverUrl.isNotBlank()
        }.distinctUntilChanged()

    suspend fun setServerUrl(value: String) = update { it.copy(serverUrl = value.trim()) }

    suspend fun setDeviceName(value: String) = update { it.copy(deviceName = value) }

    suspend fun setMaxSeq(value: Int) = update { it.copy(maxSeq = value) }

    suspend fun setTableCount(value: Int) = update { it.copy(tableCount = value) }

    suspend fun setCutoffHour(value: Int) = update { it.copy(cutoffHour = value) }

    suspend fun setCatalogVersion(value: Int) = update { it.copy(catalogVersion = value) }

    /** Konfiguration vom Server uebernehmen. */
    suspend fun apply(config: at.heuriger.kassa.wire.ServerConfigDto) = update {
        it.copy(
            tableCount = config.tableCount,
            cutoffHour = config.businessDayCutoffHour,
            catalogVersion = config.catalogVersion,
        )
    }

    /** Nach dem Login: Token in den sicheren Speicher, `deviceId` vom Server. */
    suspend fun apply(login: LoginResponse) {
        tokenStore.write(login.token)
        _token.value = login.token
        update { it.copy(deviceId = login.deviceId) }
    }

    /**
     * Abmelden. `maxSeq` faellt auf 0, damit die naechste Anmeldung komplett neu
     * laedt. Server-URL und Geraetename bleiben — die tippt sonst jemand von Hand
     * neu ein.
     */
    suspend fun logout() {
        tokenStore.write(null)
        _token.value = null
        update { it.copy(maxSeq = 0) }
    }

    private suspend fun update(transform: (Settings) -> Settings) {
        val next = _settings.updateAndGet(transform)
        dataStore.edit { prefs -> next.writeInto(prefs) }
    }

    data class Settings(
        val serverUrl: String = "",
        val deviceName: String = DEFAULT_DEVICE_NAME,
        val deviceId: String,
        /** Hoechste vom Server bestaetigte Sequenznummer. */
        val maxSeq: Int = 0,
        val tableCount: Int = DEFAULT_TABLE_COUNT,
        val cutoffHour: Int = DEFAULT_CUTOFF_HOUR,
        val catalogVersion: Int = 0,
    ) {
        internal fun writeInto(prefs: androidx.datastore.preferences.core.MutablePreferences) {
            prefs[Keys.SERVER_URL] = serverUrl
            prefs[Keys.DEVICE_NAME] = deviceName
            prefs[Keys.DEVICE_ID] = deviceId
            prefs[Keys.MAX_SEQ] = maxSeq
            prefs[Keys.TABLE_COUNT] = tableCount
            prefs[Keys.CUTOFF_HOUR] = cutoffHour
            prefs[Keys.CATALOG_VERSION] = catalogVersion
        }
    }

    /** Schluesselnamen identisch zu iOS, damit ein Export spaeter lesbar bleibt. */
    private object Keys {
        val SERVER_URL = stringPreferencesKey("serverURL")
        val DEVICE_NAME = stringPreferencesKey("deviceName")
        val DEVICE_ID = stringPreferencesKey("deviceId")
        val MAX_SEQ = intPreferencesKey("maxSeq")
        val TABLE_COUNT = intPreferencesKey("tableCount")
        val CUTOFF_HOUR = intPreferencesKey("cutoffHour")
        val CATALOG_VERSION = intPreferencesKey("catalogVersion")
    }

    companion object {
        const val DEFAULT_DEVICE_NAME = "Android-Kassa"
        const val DEFAULT_TABLE_COUNT = 40
        const val DEFAULT_CUTOFF_HOUR = 6

        /**
         * Liest den persistierten Stand einmal ein und baut den Spiegel auf.
         * Eine fehlende `deviceId` wird hier erzeugt und sofort festgeschrieben —
         * sie muss ueber Neustarts stabil bleiben, sonst zaehlt der Server das
         * Geraet jedes Mal neu.
         */
        suspend fun create(
            dataStore: DataStore<Preferences>,
            tokenStore: TokenStore,
        ): SettingsStore {
            val prefs = dataStore.data.first()
            val deviceId = prefs[Keys.DEVICE_ID] ?: UUID.randomUUID().toString()

            val initial = Settings(
                serverUrl = prefs[Keys.SERVER_URL] ?: "",
                deviceName = prefs[Keys.DEVICE_NAME] ?: DEFAULT_DEVICE_NAME,
                deviceId = deviceId,
                maxSeq = prefs[Keys.MAX_SEQ] ?: 0,
                tableCount = prefs[Keys.TABLE_COUNT] ?: DEFAULT_TABLE_COUNT,
                cutoffHour = prefs[Keys.CUTOFF_HOUR] ?: DEFAULT_CUTOFF_HOUR,
                catalogVersion = prefs[Keys.CATALOG_VERSION] ?: 0,
            )
            if (prefs[Keys.DEVICE_ID] == null) {
                dataStore.edit { initial.writeInto(it) }
            }
            return SettingsStore(dataStore, tokenStore, initial)
        }
    }
}
