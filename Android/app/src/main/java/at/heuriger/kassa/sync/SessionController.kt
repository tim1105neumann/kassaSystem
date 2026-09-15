package at.heuriger.kassa.sync

import at.heuriger.kassa.data.ConnectionState
import at.heuriger.kassa.data.KassaStore
import at.heuriger.kassa.data.SettingsStore
import at.heuriger.kassa.net.ApiException
import at.heuriger.kassa.net.KassaApi
import at.heuriger.kassa.wire.CreatePrintRequestRequest
import at.heuriger.kassa.wire.DayReportDto
import at.heuriger.kassa.wire.KassaClock
import at.heuriger.kassa.wire.SettlementLineSelection
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Was der Anmeldebildschirm sehen muss. */
data class LoginUiState(
    val isLoggingIn: Boolean = false,
    val loginError: String? = null,
)

/**
 * Pendant zu `AppModel` aus `App/Sources/KassaApp.swift`, aber app-weit statt
 * ViewModel: Anmeldung, Abmeldung und die wenigen Aktionen, die das Netz
 * anstossen, ueberleben so jeden Bildschirmwechsel.
 */
class SessionController(
    private val store: KassaStore,
    private val settings: SettingsStore,
    private val engine: SyncEngine,
    /**
     * Richtet den Client auf Server und Token aus — das Pendant zu iOS'
     * `client.configure(baseURL:token:)`. Als Funktion herein, damit ein
     * Test-Fake schlicht nichts tut.
     */
    private val configureApi: (baseUrl: String?, token: String?) -> Unit,
    private val apiProvider: () -> KassaApi,
) {
    private val _loginState = MutableStateFlow(LoginUiState())
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    // MARK: - Lebenszyklus

    suspend fun startup() {
        applyConfiguration()
        if (!settings.isLoggedIn) {
            store.setConnection(ConnectionState.NEEDS_LOGIN)
            return
        }
        engine.refreshCatalog()
        engine.start()
    }

    /**
     * Mobilfunkverbindungen brechen still weg — beim Zurueckkommen in den
     * Vordergrund immer frisch synchronisieren.
     */
    fun syncSoon() = engine.requestSync()

    suspend fun login(password: String) {
        _loginState.update { it.copy(isLoggingIn = true, loginError = null) }
        try {
            if (settings.serverUrl.isBlank()) {
                _loginState.update { it.copy(loginError = "Bitte zuerst eine Server-Adresse eintragen.") }
                return
            }

            // Ohne Token anmelden, sonst schickt der Client ein abgelaufenes mit.
            configureApi(settings.serverUrl, null)
            try {
                val response = apiProvider().login(password, settings.deviceName)
                settings.apply(response)
                store.setConnection(ConnectionState.SYNCING)
                applyConfiguration()
                engine.refreshCatalog()
                engine.start()
                engine.syncNow()
            } catch (error: ApiException) {
                _loginState.update { it.copy(loginError = error.germanMessage) }
            }
        } finally {
            _loginState.update { it.copy(isLoggingIn = false) }
        }
    }

    suspend fun logout() {
        engine.stop()
        settings.logout()
        store.wipeAll()
        store.setConnection(ConnectionState.NEEDS_LOGIN)
        applyConfiguration()
    }

    suspend fun applyServerUrlChange() {
        engine.stop()
        applyConfiguration()
        if (!settings.isLoggedIn) return
        engine.start()
    }

    // MARK: - Aktionen, die direkt ans Netz gehen

    /**
     * Aufstellung zum Nachrechnen am Kuechendrucker.
     *
     * Geht bewusst **nicht** ueber die Offline-Queue: ein Zettel, der zwanzig
     * Minuten spaeter aus dem Drucker kommt, hilft am Tisch niemandem mehr.
     * Ergebnis ist `null` bei Erfolg, sonst die Meldung fuer den Kellner.
     */
    suspend fun printOverview(
        tableNumber: Int,
        selections: List<SettlementLineSelection>,
    ): String? = try {
        apiProvider().createPrintRequest(
            CreatePrintRequestRequest(
                // Neue ID pro Druck — dieselbe ergaebe am Server nur einen Zettel.
                id = UUID.randomUUID(),
                tableNumber = tableNumber,
                lines = selections,
                requestedAt = KassaClock.now(),
            )
        )
        null
    } catch (error: ApiException) {
        error.germanMessage
    }

    suspend fun dayReport(businessDay: String): DayReportDto =
        apiProvider().dayReport(businessDay)

    suspend fun discardCommand(id: UUID) = store.discardCommand(id)

    private fun applyConfiguration() =
        configureApi(settings.serverUrl.ifBlank { null }, settings.token.value)
}
