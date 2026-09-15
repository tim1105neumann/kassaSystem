package at.heuriger.kassa.net

import at.heuriger.kassa.wire.ArticleDto
import at.heuriger.kassa.wire.CreateOrderLinesRequest
import at.heuriger.kassa.wire.CreatePrintRequestRequest
import at.heuriger.kassa.wire.CreateSettlementRequest
import at.heuriger.kassa.wire.DayReportDto
import at.heuriger.kassa.wire.LoginResponse
import at.heuriger.kassa.wire.PrintRequestDto
import at.heuriger.kassa.wire.ServerConfigDto
import at.heuriger.kassa.wire.SettlementDto
import at.heuriger.kassa.wire.SyncPing
import at.heuriger.kassa.wire.SyncResponse
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Abstraktion ueber den Server, damit Tests einen Fake einsetzen koennen.
 * Spiegel von `protocol KassaAPI` aus `App/Sources/Sync/KassaAPI.swift`.
 *
 * Jede Methode wirft [ApiException] und nichts anderes.
 */
interface KassaApi {
    suspend fun login(password: String, deviceName: String): LoginResponse

    suspend fun config(): ServerConfigDto

    suspend fun articles(): List<ArticleDto>

    suspend fun sync(since: Int): SyncResponse

    suspend fun createOrderLines(request: CreateOrderLinesRequest)

    suspend fun voidLine(id: UUID)

    suspend fun createSettlement(request: CreateSettlementRequest): SettlementDto

    /**
     * Abweichung vom Swift-Protokoll, das hier nichts zurueckgibt: der Server
     * antwortet mit dem fertig aufgeloesten [PrintRequestDto] (Namen, Preise und
     * Summe kommen von ihm, nicht vom Client). Die Antwort wegzuwerfen hiesse,
     * sie gleich darauf ueber /sync wieder holen zu muessen.
     */
    suspend fun createPrintRequest(request: CreatePrintRequestRequest): PrintRequestDto

    suspend fun dayReport(businessDay: String): DayReportDto

    /**
     * Push-Signal "es gibt Neues". Der Flow endet, wenn die Verbindung abreisst;
     * der Aufrufer baut sie dann neu auf.
     */
    fun syncPings(): Flow<SyncPing>
}
