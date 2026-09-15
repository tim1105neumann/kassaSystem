package at.heuriger.kassa.sync

import android.util.Log

import at.heuriger.kassa.data.CommandKind
import at.heuriger.kassa.data.ConnectionState
import at.heuriger.kassa.data.KassaStore
import at.heuriger.kassa.data.PendingCommandSnapshot
import at.heuriger.kassa.data.SettingsStore
import at.heuriger.kassa.data.VoidLineCommand
import at.heuriger.kassa.net.ApiException
import at.heuriger.kassa.net.KassaApi
import at.heuriger.kassa.wire.CreateOrderLinesRequest
import at.heuriger.kassa.wire.CreateSettlementRequest
import at.heuriger.kassa.wire.KassaJson
import at.heuriger.kassa.wire.SyncResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Arbeitet die Offline-Queue ab, zieht danach das Delta und haelt den
 * WebSocket. Alles, was persistiert wird, geht ueber den [KassaStore].
 *
 * Spiegel von `actor SyncEngine` aus `App/Sources/Sync/SyncEngine.swift`.
 *
 * **Isolation.** Statt iOS' `isSyncing`-Flag, das einen Sync-Wunsch waehrend
 * eines laufenden Syncs ersatzlos verwirft, traegt ein [Channel] mit
 * [Channel.CONFLATED] genau einen gemerkten Wunsch und fuehrt ihn direkt danach
 * aus. Gelesen wird der Kanal nur an einer Stelle.
 *
 * Diese Stelle ist zugleich der Poll-Loop: `syncNow()` und das anschliessende
 * Warten liegen in **derselben** Coroutine, die entweder auf einen Wunsch
 * wartet oder nach dem Poll-Intervall von selbst aufwacht. Genau so macht es
 * auch iOS' `pollTask`. Zwei getrennte Coroutinen haetten sich [backoff] teilen
 * muessen — ein Feld, das der eine schreibt und der andere liest.
 */
class SyncEngine(
    private val store: KassaStore,
    private val settings: SettingsStore,
    /**
     * Der Client kann sich zur Laufzeit aendern (andere Server-Adresse, neues
     * Token), deshalb bei jedem Zugriff frisch geholt statt einmal gehalten.
     */
    private val apiProvider: () -> KassaApi,
    private val scope: CoroutineScope,
    private val pollInterval: Duration = 10.seconds,
    private val maxBackoff: Duration = 60.seconds,
    /** Wartezeit, bis der abgerissene WebSocket neu aufgebaut wird. */
    private val reconnectDelay: Duration = 5.seconds,
) {
    private val api: KassaApi get() = apiProvider()
    private val json = KassaJson.instance

    private val triggers = Channel<Unit>(Channel.CONFLATED)

    /**
     * [syncNow] ist auch direkt aufrufbar (Login, Rueckkehr in den Vordergrund,
     * Tests). Damit ist die Schleife nicht der einzige Aufrufer, und zwei Syncs
     * duerfen sich nicht ueberlappen — sonst ginge derselbe Command zweimal
     * raus. Anders als iOS' `guard !isSyncing` wird der zweite Aufruf hier
     * nicht verworfen, sondern wartet.
     */
    private val syncLock = Mutex()

    /**
     * Backoff fuer den Poll-Loop: bei Netzproblemen nicht alle 10 s haemmern.
     * Als [MutableStateFlow], weil ihn ein direkter [syncNow]-Aufruf aus einer
     * anderen Coroutine schreiben kann.
     */
    private val backoff = MutableStateFlow(Duration.ZERO)

    private var loopJob: Job? = null
    private var socketJob: Job? = null

    // MARK: - Lebenszyklus

    fun start() {
        if (loopJob != null) return

        loopJob = scope.launch {
            while (isActive) {
                syncNow()
                // Entweder ein Sync-Wunsch trifft ein oder das Intervall laeuft ab.
                withTimeoutOrNull(currentInterval()) { triggers.receive() }
            }
        }

        socketJob = scope.launch {
            while (isActive) {
                try {
                    api.syncPings().collect { syncNow() }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Verbindung weg — unten wird gewartet und neu aufgebaut.
                }
                delay(reconnectDelay)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        socketJob?.cancel()
        socketJob = null
    }

    /** „Es gibt Neues" — hoechstens ein Wunsch wird gemerkt. */
    fun requestSync() {
        triggers.trySend(Unit)
    }

    private fun currentInterval(): Duration = maxOf(pollInterval, backoff.value)

    // MARK: - Sync

    suspend fun syncNow() = syncLock.withLock {
        store.setConnection(ConnectionState.SYNCING)

        when (val result = drainQueue()) {
            is QueueResult.Paused -> {
                report(result.error)
                return@withLock
            }
            QueueResult.Drained -> Unit
        }

        try {
            val response = api.sync(settings.maxSeq)
            store.merge(response)
            store.setConnection(ConnectionState.CONNECTED)
            store.setLastError(null)
            backoff.value = Duration.ZERO
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: ApiException) {
            report(error)
        } catch (error: Throwable) {
            report(ApiException.Transport(error))
        }
    }

    /** Laedt Konfiguration und Katalog. Nach dem Login und beim Kaltstart. */
    suspend fun refreshCatalog() {
        try {
            settings.apply(api.config())
            store.replaceArticles(api.articles())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: ApiException) {
            report(error)
        } catch (error: Throwable) {
            report(ApiException.Transport(error))
        }
    }

    /** Kompletten Spiegel neu ziehen — nach einem Konflikt beim Kassieren. */
    suspend fun forceFullReload() {
        try {
            val response = api.sync(since = 0)
            store.merge(
                SyncResponse(
                    lines = response.lines,
                    settlements = response.settlements,
                    maxSeq = response.maxSeq,
                    isFullReload = true,
                )
            )
            store.setConnection(ConnectionState.CONNECTED)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: ApiException) {
            report(error)
        } catch (error: Throwable) {
            report(ApiException.Transport(error))
        }
    }

    // MARK: - Queue

    private sealed interface QueueResult {
        data object Drained : QueueResult

        /**
         * Netz- oder Auth-Problem: die Reihenfolge muss erhalten bleiben, also
         * hier abbrechen und beim naechsten Lauf weitermachen.
         */
        data class Paused(val error: ApiException) : QueueResult
    }

    private suspend fun drainQueue(): QueueResult {
        while (true) {
            val command = store.nextPendingCommand() ?: return QueueResult.Drained
            try {
                send(command)
                store.completeCommand(command.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: ApiException) {
                when (error.disposition) {
                    ApiException.Disposition.RETRY,
                    ApiException.Disposition.NEEDS_LOGIN,
                    -> {
                        store.recordFailure(command.id, error.germanMessage, permanent = false)
                        return QueueResult.Paused(error)
                    }
                    // Der naechste Befehl kommt trotzdem dran, sonst steht die
                    // ganze Queue hinter einem einzigen 4xx still.
                    ApiException.Disposition.PERMANENT -> handlePermanentFailure(command, error)
                }
            } catch (error: Throwable) {
                store.recordFailure(command.id, error.message ?: "Unbekannter Fehler", permanent = false)
                return QueueResult.Paused(ApiException.Transport(error))
            }
        }
    }

    /**
     * Der heikelste Pfad. Ein abgelehnter Kassiervorgang darf lokal nicht als
     * kassiert stehen bleiben und auf keinen Fall stillschweigend erneut
     * gebucht werden: Meldung an den Kellner, lokale Zeilen wieder oeffnen,
     * Befehl entfernen, Serverstand komplett neu holen.
     */
    private suspend fun handlePermanentFailure(
        command: PendingCommandSnapshot,
        error: ApiException,
    ) {
        if (command.kind != CommandKind.SETTLE) {
            store.recordFailure(command.id, error.germanMessage, permanent = true)
            return
        }

        val request = decodedSettlement(command)
        val message = if (error is ApiException.SettlementConflict && request != null) {
            error.conflict.germanMessage(request.tableNumber)
        } else {
            error.germanMessage
        }
        store.setConflict(message)

        if (request != null) store.rollbackSettlement(request.id)
        store.completeCommand(command.id)
        forceFullReload()
    }

    private fun decodedSettlement(command: PendingCommandSnapshot): CreateSettlementRequest? =
        runCatching {
            json.decodeFromString(CreateSettlementRequest.serializer(), command.payload)
        }.getOrNull()

    private suspend fun send(command: PendingCommandSnapshot) {
        when (command.kind) {
            CommandKind.ADD_LINES ->
                api.createOrderLines(decode(CreateOrderLinesRequest.serializer(), command.payload))

            CommandKind.VOID_LINE ->
                api.voidLine(decode(VoidLineCommand.serializer(), command.payload).lineId)

            CommandKind.SETTLE ->
                api.createSettlement(decode(CreateSettlementRequest.serializer(), command.payload))
        }
    }

    private fun <T> decode(
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: String,
    ): T = try {
        json.decodeFromString(serializer, payload)
    } catch (error: Exception) {
        // Ein unlesbarer Payload heilt sich nicht — als PERMANENT einstufen,
        // damit der Befehl die Queue nicht ewig blockiert.
        throw ApiException.Decoding(error)
    }

    // MARK: - Status

    private fun report(error: ApiException) {
        // Die Meldung fuer den Kellner lautet bei jedem Netzproblem gleich
        // ("Keine Verbindung zum Server."). Fuer die Fehlersuche ist das zu
        // wenig: Zeitablauf, blockierter Klartext und fehlende Netzwerk-
        // berechtigung sehen von aussen identisch aus. Die Ursache gehoert
        // deshalb ins Log, nicht nur in die Datenbank.
        Log.w(LOG_TAG, "Sync-Fehler: ${error.germanMessage}", error.cause ?: error)

        store.setConnection(
            if (error.disposition == ApiException.Disposition.NEEDS_LOGIN) {
                ConnectionState.NEEDS_LOGIN
            } else {
                ConnectionState.OFFLINE
            }
        )
        store.setLastError(error.germanMessage)

        if (error.isOffline) {
            backoff.value = minOf(maxOf(backoff.value * 2, pollInterval * 2), maxBackoff)
        }
    }

    private companion object {
        const val LOG_TAG = "KassaSync"
    }
}
