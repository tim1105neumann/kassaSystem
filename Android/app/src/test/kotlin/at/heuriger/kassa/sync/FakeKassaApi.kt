package at.heuriger.kassa.sync

import at.heuriger.kassa.net.ApiException
import at.heuriger.kassa.net.KassaApi
import at.heuriger.kassa.wire.ArticleDto
import at.heuriger.kassa.wire.BusinessDay
import at.heuriger.kassa.wire.CreateDayReportPrintRequestRequest
import at.heuriger.kassa.wire.CreateOrderLinesRequest
import at.heuriger.kassa.wire.CreatePrintRequestRequest
import at.heuriger.kassa.wire.CreateSettlementRequest
import at.heuriger.kassa.wire.DayReportDto
import at.heuriger.kassa.wire.DayReportPrintRequestDto
import at.heuriger.kassa.wire.KassaClock
import at.heuriger.kassa.wire.LoginResponse
import at.heuriger.kassa.wire.OrderLineDto
import at.heuriger.kassa.wire.PrintRequestDto
import at.heuriger.kassa.wire.ServerConfigDto
import at.heuriger.kassa.wire.SettlementDto
import at.heuriger.kassa.wire.SyncPing
import at.heuriger.kassa.wire.SyncResponse
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Minimaler Fake-Server im Speicher. Spiegel von `App/Tests/FakeKassaAPI.swift`.
 *
 * Idempotent auf den client-vergebenen UUIDs — genau wie der echte Server, damit
 * ein doppelt gesendeter Command keine Doppelbuchung erzeugt.
 *
 * Swift nutzt dafuer einen `actor`; hier serialisiert ein [Mutex] jeden Zugriff.
 * Deshalb sind auch die Abfragen (`serverLineCount` und Co.) `suspend`.
 */
class FakeKassaApi(articles: List<ArticleDto> = DEFAULT_ARTICLES) : KassaApi {

    private val lock = Mutex()

    private val articleCatalog = articles.toMutableList()
    private val lines = linkedMapOf<UUID, OrderLineDto>()
    private val settlements = linkedMapOf<UUID, SettlementDto>()
    private val printRequests = linkedMapOf<UUID, CreatePrintRequestRequest>()
    private val dayReportPrintRequests = linkedMapOf<UUID, DayReportPrintRequestDto>()
    private var seq = 0

    /** Alle Aufrufe scheitern mit einem Netzfehler. */
    private var offline = false

    /**
     * Wendet den naechsten Buchungs-Command an und wirft danach trotzdem einen
     * Netzfehler — die verlorene Antwort, die zum erneuten Senden fuehrt.
     */
    private var loseNextResponse = false

    private var nextOrderLinesError: ApiException? = null
    private var nextSettlementError: ApiException? = null
    private var nextPrintRequestError: ApiException? = null
    private var nextDayReportPrintRequestError: ApiException? = null

    private var orderLineCalls = 0
    private var settlementCalls = 0
    private var voidCalls = 0
    private var printRequestCalls = 0
    private var dayReportPrintRequestCalls = 0
    private var syncCalls = 0

    /** Push-Kanal, den ein Test selbst bedienen kann. */
    val pings = MutableSharedFlow<SyncPing>(extraBufferCapacity = 16)

    /**
     * Haelt [sync] an, bis der Test das Tor oeffnet. Ohne das liesse sich
     * „waehrend ein Sync laeuft" nur erhoffen, nicht erzwingen.
     */
    @Volatile
    var syncGate: CompletableDeferred<Unit>? = null

    // MARK: - Steuerung

    suspend fun setOffline(value: Boolean) = lock.withLock { offline = value }

    suspend fun setLoseNextResponse(value: Boolean) = lock.withLock { loseNextResponse = value }

    suspend fun setNextOrderLinesError(value: ApiException?) =
        lock.withLock { nextOrderLinesError = value }

    suspend fun setNextSettlementError(value: ApiException?) =
        lock.withLock { nextSettlementError = value }

    suspend fun setNextPrintRequestError(value: ApiException?) =
        lock.withLock { nextPrintRequestError = value }

    suspend fun setNextDayReportPrintRequestError(value: ApiException?) =
        lock.withLock { nextDayReportPrintRequestError = value }

    // MARK: - Abfragen

    suspend fun serverLineCount(): Int = lock.withLock { lines.size }

    suspend fun serverLine(id: UUID): OrderLineDto? = lock.withLock { lines[id] }

    suspend fun serverSettlementCount(): Int = lock.withLock { settlements.size }

    suspend fun serverPrintRequestCount(): Int = lock.withLock { printRequests.size }

    suspend fun serverPrintRequest(id: UUID): CreatePrintRequestRequest? =
        lock.withLock { printRequests[id] }

    suspend fun serverDayReportPrintRequestCount(): Int =
        lock.withLock { dayReportPrintRequests.size }

    suspend fun serverDayReportPrintRequests(): List<DayReportPrintRequestDto> =
        lock.withLock { dayReportPrintRequests.values.toList() }

    suspend fun orderLineCallCount(): Int = lock.withLock { orderLineCalls }

    suspend fun settlementCallCount(): Int = lock.withLock { settlementCalls }

    suspend fun voidCallCount(): Int = lock.withLock { voidCalls }

    suspend fun printRequestCallCount(): Int = lock.withLock { printRequestCalls }

    suspend fun dayReportPrintRequestCallCount(): Int =
        lock.withLock { dayReportPrintRequestCalls }

    suspend fun syncCallCount(): Int = lock.withLock { syncCalls }

    // MARK: - KassaApi

    override suspend fun login(password: String, deviceName: String): LoginResponse =
        lock.withLock {
            guardOnline()
            LoginResponse(token = "test-token", deviceId = DEVICE_ID)
        }

    override suspend fun config(): ServerConfigDto = lock.withLock {
        guardOnline()
        ServerConfigDto(tableCount = 40, businessDayCutoffHour = 6, catalogVersion = 1)
    }

    override suspend fun articles(): List<ArticleDto> = lock.withLock {
        guardOnline()
        articleCatalog.toList()
    }

    override suspend fun sync(since: Int): SyncResponse {
        // Zaehler zuerst, damit ein Test das Eintreffen sieht, bevor das Tor
        // haelt — sonst waere „warte, bis der Sync laeuft" nicht beobachtbar.
        lock.withLock { syncCalls += 1 }
        syncGate?.await()

        return lock.withLock {
            guardOnline()
            SyncResponse(
                lines = lines.values.filter { it.updatedSeq > since }.sortedBy { it.updatedSeq },
                settlements = settlements.values.filter { it.updatedSeq > since }.sortedBy { it.updatedSeq },
                maxSeq = seq,
                isFullReload = false,
            )
        }
    }

    override suspend fun createOrderLines(request: CreateOrderLinesRequest): Unit = lock.withLock {
        orderLineCalls += 1
        guardOnline()
        nextOrderLinesError?.let {
            nextOrderLinesError = null
            throw it
        }

        for (new in request.lines) {
            if (lines.containsKey(new.id)) continue
            val article = articleCatalog.firstOrNull { it.id == new.articleId }
                ?: throw ApiException.Server(status = 400, reason = "unbekannter Artikel")
            seq += 1
            lines[new.id] = OrderLineDto(
                id = new.id,
                tableNumber = new.tableNumber,
                articleId = new.articleId,
                nameSnapshot = article.name,
                unitPriceCents = article.priceCents,
                qty = new.qty,
                createdAt = new.createdAt,
                deviceId = DEVICE_ID,
                updatedSeq = seq,
                // Wie der echte Server: die Notiz kommt im Delta zurueck. Ohne
                // das zeigt kein Queue-Test, ob sie die Leitung erreicht.
                note = new.note,
            )
        }

        if (loseNextResponse) {
            loseNextResponse = false
            throw ApiException.Transport(IOException("Verbindung verloren"))
        }
    }

    override suspend fun voidLine(id: UUID): Unit = lock.withLock {
        voidCalls += 1
        guardOnline()
        val line = lines[id] ?: throw ApiException.Server(status = 404, reason = "unbekannte Zeile")
        if (line.voidedAt == null) {
            seq += 1
            lines[id] = line.copy(voidedAt = KassaClock.now(), updatedSeq = seq)
        }
    }

    override suspend fun createSettlement(request: CreateSettlementRequest): SettlementDto =
        lock.withLock {
            settlementCalls += 1
            guardOnline()
            nextSettlementError?.let {
                nextSettlementError = null
                throw it
            }
            settlements[request.id]?.let { return@withLock it }

            for (selection in request.lines) {
                val line = lines[selection.lineId] ?: continue
                seq += 1
                lines[selection.lineId] = line.copy(settlementId = request.id, updatedSeq = seq)
            }
            seq += 1
            val settlement = SettlementDto(
                id = request.id,
                tableNumber = request.tableNumber,
                totalCents = request.amountCents,
                paidAt = request.paidAt,
                deviceId = DEVICE_ID,
                businessDay = BusinessDay.day(request.paidAt, cutoffHour = 6),
                updatedSeq = seq,
                tipCents = request.tipCents,
            )
            settlements[request.id] = settlement
            settlement
        }

    override suspend fun createPrintRequest(request: CreatePrintRequestRequest): PrintRequestDto =
        lock.withLock {
            printRequestCalls += 1
            guardOnline()
            nextPrintRequestError?.let {
                nextPrintRequestError = null
                throw it
            }
            // Idempotent auf der Client-ID: derselbe Auftrag ergibt einen Zettel.
            printRequests.getOrPut(request.id) { request }

            val items = request.lines.mapNotNull { selection ->
                val line = lines[selection.lineId] ?: return@mapNotNull null
                PrintRequestDto.Item(
                    name = line.nameSnapshot,
                    qty = selection.qty,
                    unitPriceCents = line.unitPriceCents,
                )
            }
            seq += 1
            PrintRequestDto(
                id = request.id,
                tableNumber = request.tableNumber,
                items = items,
                totalCents = items.sumOf { it.unitPriceCents * it.qty },
                requestedAt = request.requestedAt,
                deviceId = DEVICE_ID,
                updatedSeq = seq,
            )
        }

    override suspend fun createDayReportPrintRequest(
        request: CreateDayReportPrintRequestRequest,
    ): DayReportPrintRequestDto = lock.withLock {
        dayReportPrintRequestCalls += 1
        guardOnline()
        nextDayReportPrintRequestError?.let {
            nextDayReportPrintRequestError = null
            throw it
        }
        // Idempotent auf der Client-ID: derselbe Auftrag ergibt einen Zettel.
        dayReportPrintRequests[request.id]?.let { return@withLock it }

        seq += 1
        val created = DayReportPrintRequestDto(
            id = request.id,
            businessDay = request.businessDay,
            requestedAt = request.requestedAt,
            // Wie der echte Server: die Geraete-ID kommt aus dem Token, nie aus
            // dem Koerper.
            deviceId = DEVICE_ID,
            updatedSeq = seq,
        )
        dayReportPrintRequests[request.id] = created
        created
    }

    override suspend fun dayReport(businessDay: String): DayReportDto = lock.withLock {
        guardOnline()
        val matching = settlements.values.filter { it.businessDay == businessDay }
        DayReportDto(
            businessDay = businessDay,
            totalCents = matching.sumOf { it.totalCents },
            settlementCount = matching.size,
            byCategory = emptyList(),
            topArticles = emptyList(),
            settlements = matching.sortedBy { it.paidAt },
            tipCents = matching.sumOf { it.tipCents },
        )
    }

    override fun syncPings(): Flow<SyncPing> = pings

    /** Aufrufer haelt [lock]. */
    private fun guardOnline() {
        if (offline) throw ApiException.Transport(IOException("Keine Verbindung"))
    }

    companion object {
        const val DEVICE_ID = "fake-device"

        val DEFAULT_ARTICLES = listOf(
            ArticleDto(
                id = "a1", category = "Speisen", name = "Kaesekrainer mit Gebaeck",
                priceCents = 620, sortOrder = 0, active = true,
            ),
            ArticleDto(
                id = "b1", category = "Getraenke", name = "Bier, Radler 0,5 l",
                priceCents = 440, sortOrder = 10, active = true,
            ),
        )
    }
}
