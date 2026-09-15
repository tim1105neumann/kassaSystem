package at.heuriger.kassa.net

import at.heuriger.kassa.wire.ApiErrorDto
import at.heuriger.kassa.wire.ApiRoute
import at.heuriger.kassa.wire.ArticleDto
import at.heuriger.kassa.wire.CreateOrderLinesRequest
import at.heuriger.kassa.wire.CreatePrintRequestRequest
import at.heuriger.kassa.wire.CreateSettlementRequest
import at.heuriger.kassa.wire.DayReportDto
import at.heuriger.kassa.wire.KassaJson
import at.heuriger.kassa.wire.LoginRequest
import at.heuriger.kassa.wire.LoginResponse
import at.heuriger.kassa.wire.PrintRequestDto
import at.heuriger.kassa.wire.ServerConfigDto
import at.heuriger.kassa.wire.SettlementConflictDto
import at.heuriger.kassa.wire.SettlementDto
import at.heuriger.kassa.wire.SyncPing
import at.heuriger.kassa.wire.SyncResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * Ktor-Umsetzung von [KassaApi], Zeile fuer Zeile am Verhalten von
 * `App/Sources/Sync/APIClient.swift` ausgerichtet.
 *
 * Bewusst ohne das ContentNegotiation-Plugin: jeder Koerper wird mit
 * [KassaJson] von Hand serialisiert und aus dem gelesenen Text dekodiert.
 * Grund ist der 409-Pfad — dort muss derselbe Koerper *zweimal* betrachtet
 * werden (erst als [SettlementConflictDto], bei Misserfolg als [ApiErrorDto]),
 * und `body<T>()` konsumiert den Kanal beim ersten Zugriff unwiderruflich.
 * Einmal `bodyAsText()` und danach reine String-Verarbeitung umgeht das Problem
 * ganz, statt es an einer Stelle zu umschiffen.
 */
class KtorKassaApi(
    baseUrl: String? = null,
    token: String? = null,
    engine: HttpClientEngine = OkHttp.create(),
) : KassaApi {

    @Volatile
    private var baseUrl: String? = baseUrl?.trimEnd('/')

    @Volatile
    private var token: String? = token

    private val json = KassaJson.instance

    private val client = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            // Spiegelt URLSessions timeoutIntervalForRequest = 15.
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
        install(WebSockets)
        defaultRequest {
            headers.append(HttpHeaders.Accept, ContentType.Application.Json.toString())
            // Pro Request ausgewertet, liest also immer den aktuellen Token.
            this@KtorKassaApi.token?.let {
                headers.append(HttpHeaders.Authorization, "Bearer $it")
            }
        }
    }

    fun configure(baseUrl: String?, token: String?) {
        this.baseUrl = baseUrl?.trimEnd('/')
        this.token = token
    }

    fun close() = client.close()

    // MARK: - Endpunkte

    override suspend fun login(password: String, deviceName: String): LoginResponse = send(
        route = ApiRoute.LOGIN,
        method = HttpMethod.Post,
        serializer = LoginResponse.serializer(),
        body = encode(LoginRequest.serializer(), LoginRequest(password, deviceName)),
        requiresToken = false,
    )

    override suspend fun config(): ServerConfigDto =
        send(ApiRoute.CONFIG, HttpMethod.Get, ServerConfigDto.serializer())

    override suspend fun articles(): List<ArticleDto> =
        send(ApiRoute.ARTICLES, HttpMethod.Get, ListSerializer(ArticleDto.serializer()))

    override suspend fun sync(since: Int): SyncResponse = send(
        route = ApiRoute.SYNC,
        method = HttpMethod.Get,
        serializer = SyncResponse.serializer(),
        query = listOf("since" to since.toString()),
    )

    override suspend fun createOrderLines(request: CreateOrderLinesRequest) {
        sendRaw(
            route = ApiRoute.ORDER_LINES,
            method = HttpMethod.Post,
            body = encode(CreateOrderLinesRequest.serializer(), request),
        )
    }

    override suspend fun voidLine(id: UUID) {
        sendRaw(route = ApiRoute.voidLine(id), method = HttpMethod.Post)
    }

    override suspend fun createSettlement(request: CreateSettlementRequest): SettlementDto = send(
        route = ApiRoute.SETTLEMENTS,
        method = HttpMethod.Post,
        serializer = SettlementDto.serializer(),
        body = encode(CreateSettlementRequest.serializer(), request),
    )

    override suspend fun createPrintRequest(request: CreatePrintRequestRequest): PrintRequestDto = send(
        route = ApiRoute.PRINT_REQUESTS,
        method = HttpMethod.Post,
        serializer = PrintRequestDto.serializer(),
        body = encode(CreatePrintRequestRequest.serializer(), request),
    )

    override suspend fun dayReport(businessDay: String): DayReportDto = send(
        route = ApiRoute.DAY_REPORT,
        method = HttpMethod.Get,
        serializer = DayReportDto.serializer(),
        // Der Server liest den Parameter "date", nicht "businessDay".
        query = listOf("date" to businessDay),
    )

    // MARK: - WebSocket

    override fun syncPings(): Flow<SyncPing> = channelFlow {
        val base = baseUrl ?: return@channelFlow
        val bearer = token ?: return@channelFlow

        try {
            client.webSocket(
                urlString = webSocketUrl(base),
                request = { header(HttpHeaders.Authorization, "Bearer $bearer") },
            ) {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val ping = try {
                        json.decodeFromString(SyncPing.serializer(), frame.readText())
                    } catch (_: Exception) {
                        // Ein unlesbarer Frame beendet den Stream nicht.
                        continue
                    }
                    send(ping)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Verbindung abgerissen — der Flow endet, der Aufrufer baut neu auf.
        }
    }

    private fun webSocketUrl(base: String): String {
        val socketBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$socketBase/${ApiRoute.WEB_SOCKET}"
    }

    // MARK: - Transport

    private fun <T> encode(serializer: KSerializer<T>, value: T): String =
        try {
            json.encodeToString(serializer, value)
        } catch (error: Exception) {
            throw ApiException.Decoding(error)
        }

    private suspend fun <T> send(
        route: String,
        method: HttpMethod,
        serializer: KSerializer<T>,
        query: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        requiresToken: Boolean = true,
    ): T {
        val text = sendRaw(route, method, query, body, requiresToken)
        return try {
            json.decodeFromString(serializer, text)
        } catch (error: Exception) {
            throw ApiException.Decoding(error)
        }
    }

    private suspend fun sendRaw(
        route: String,
        method: HttpMethod,
        query: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        requiresToken: Boolean = true,
    ): String {
        val base = baseUrl ?: throw ApiException.NotConfigured
        if (requiresToken && token == null) throw ApiException.NotConfigured

        val text: String
        val status: Int
        try {
            val response = client.request("$base/$route") {
                this.method = method
                query.forEach { (name, value) -> parameter(name, value) }
                body?.let {
                    contentType(ContentType.Application.Json)
                    setBody(it)
                }
            }
            status = response.status.value
            // Genau einmal lesen — danach ist der Kanal konsumiert.
            text = response.bodyAsText()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            throw ApiException.Transport(error)
        }

        return when {
            status in 200..299 -> text
            status == 401 || status == 403 -> throw ApiException.Unauthorized
            status == 409 -> throw conflictOrServerError(text)
            else -> throw ApiException.Server(status, reason(text))
        }
    }

    /**
     * Bei 409 antwortet der Server mit einem nackten [SettlementConflictDto]
     * ohne das sonst uebliche `"error": true`. Es gibt aber auch 409-Antworten
     * im Vapor-Standardformat, deshalb der Fallback.
     */
    private fun conflictOrServerError(text: String): ApiException {
        val conflict = try {
            json.decodeFromString(SettlementConflictDto.serializer(), text)
        } catch (_: Exception) {
            null
        }
        return conflict?.let { ApiException.SettlementConflict(it) }
            ?: ApiException.Server(409, reason(text))
    }

    /** Vapor-Standardfehler: `{"error":true,"reason":"..."}`. */
    private fun reason(text: String): String? = try {
        json.decodeFromString(ApiErrorDto.serializer(), text).reason
    } catch (_: Exception) {
        null
    }
}
