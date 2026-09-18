package at.heuriger.kassa.net.contract

import at.heuriger.kassa.net.ApiException
import at.heuriger.kassa.net.KtorKassaApi
import at.heuriger.kassa.wire.ApiRoute
import at.heuriger.kassa.wire.ArticleDto
import at.heuriger.kassa.wire.BusinessDay
import at.heuriger.kassa.wire.CreateDayReportPrintRequestRequest
import at.heuriger.kassa.wire.CreateOrderLinesRequest
import at.heuriger.kassa.wire.CreatePrintRequestRequest
import at.heuriger.kassa.wire.CreateSettlementRequest
import at.heuriger.kassa.wire.DayReportPrintRequestDto
import at.heuriger.kassa.wire.KassaClock
import at.heuriger.kassa.wire.KassaJson
import at.heuriger.kassa.wire.NewOrderLine
import at.heuriger.kassa.wire.SettlementConflictDto
import at.heuriger.kassa.wire.SettlementLineSelection
import at.heuriger.kassa.wire.SyncPing
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Vertragstests gegen den laufenden Vapor-Server.
 *
 * **Isolation**: der Server speichert echt und laeuft zwischen den Laeufen
 * weiter. Deshalb bekommt jeder Testfall frische UUIDs und eine eigene
 * Tischnummer, und es wird nie eine absolute Zeilenzahl geprueft, sondern immer
 * nur das Delta ab einem vorher gelesenen `maxSeq`. Settlements waehlen
 * ausserdem konkrete `lineId`s statt "alles am Tisch" — Altlasten eines
 * frueheren Laufs koennen die Faelle also nicht stoeren.
 */
class KassaApiContractTest {

    companion object {
        private const val DEVICE_NAME = "android-contract"

        private lateinit var api: KtorKassaApi
        private lateinit var articles: List<ArticleDto>

        /**
         * Nur fuer den rohen GET auf die Statistik-Auftraege noetig — [api] haelt
         * den Token sonst fuer sich.
         */
        private lateinit var token: String
        private var cutoffHour: Int = 6

        /** Eigener Tischbereich, damit die Faelle sich nicht gegenseitig sehen. */
        private val tableCounter = AtomicInteger(31)

        private fun nextTable(): Int = 31 + (tableCounter.getAndIncrement() - 31) % 10

        @BeforeClass
        @JvmStatic
        fun logIn() {
            val baseUrl = ContractEnv.requireBaseUrl()
            api = KtorKassaApi(baseUrl = baseUrl)
            runBlocking {
                // Genau eine Anmeldung pro Lauf — jede erzeugt am Server ein Geraet.
                val login = api.login(ContractEnv.password, DEVICE_NAME)
                token = login.token
                api.configure(baseUrl, login.token)
                articles = api.articles()
                cutoffHour = api.config().businessDayCutoffHour
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (::api.isInitialized) api.close()
        }
    }

    /**
     * Bewusst nicht `articles.first()`: der Katalog enthaelt mit "Leitungswasser"
     * einen Nullpreis-Artikel. Eine Buchung darueber haette Summe 0 und wuerde
     * die Betragspruefungen beim Kassieren wertlos machen.
     */
    private val article: ArticleDto get() = articles.first { it.priceCents > 0 }

    private suspend fun bookLine(
        table: Int,
        qty: Int = 1,
        id: UUID = UUID.randomUUID(),
        note: String? = null,
    ): UUID {
        api.createOrderLines(
            CreateOrderLinesRequest(
                listOf(
                    NewOrderLine(
                        id = id,
                        tableNumber = table,
                        articleId = article.id,
                        qty = qty,
                        createdAt = KassaClock.now(),
                        note = note,
                    ),
                ),
            ),
        )
        return id
    }

    // MARK: - Auth

    @Test
    fun `Login liefert Token und Geraete-ID`(): Unit = runBlocking {
        val response = api.login(ContractEnv.password, DEVICE_NAME)

        assertTrue("Token darf nicht leer sein", response.token.isNotBlank())
        assertTrue("Geraete-ID darf nicht leer sein", response.deviceId.isNotBlank())
    }

    @Test
    fun `falsches Passwort wird als Unauthorized gemeldet`() {
        val error = assertThrows(ApiException.Unauthorized::class.java) {
            runBlocking { api.login("definitiv-falsch", DEVICE_NAME) }
        }
        assertEquals(ApiException.Disposition.NEEDS_LOGIN, error.disposition)
    }

    @Test
    fun `ohne Token antwortet der Server mit Unauthorized`() {
        val anonymous = KtorKassaApi(baseUrl = ContractEnv.requireBaseUrl(), token = "ungueltiger-token")
        try {
            assertThrows(ApiException.Unauthorized::class.java) {
                runBlocking { anonymous.config() }
            }
        } finally {
            anonymous.close()
        }
    }

    @Test
    fun `ohne Server-URL gibt es NotConfigured statt eines Netzfehlers`() {
        val unconfigured = KtorKassaApi()
        try {
            assertThrows(ApiException.NotConfigured::class.java) {
                runBlocking { unconfigured.config() }
            }
        } finally {
            unconfigured.close()
        }
    }

    // MARK: - Katalog

    @Test
    fun `config liefert Tischanzahl und Cutoff`(): Unit = runBlocking {
        val config = api.config()

        assertEquals(40, config.tableCount)
        assertEquals(6, config.businessDayCutoffHour)
    }

    @Test
    fun `articles liefert den importierten Katalog`(): Unit = runBlocking {
        val loaded = api.articles()

        assertEquals(44, loaded.size)
        assertTrue("Alle gelieferten Artikel muessen aktiv sein", loaded.all { it.active })
        // Nicht ">0": "Leitungswasser" kostet im Katalog bewusst 0.
        assertTrue("Preise duerfen nicht negativ sein", loaded.all { it.priceCents >= 0 })
        assertTrue("Es muss Artikel mit Preis geben", loaded.any { it.priceCents > 0 })
    }

    // MARK: - Sync

    @Test
    fun `sync mit since 0 verlangt einen vollen Nachladevorgang`(): Unit = runBlocking {
        val response = api.sync(since = 0)

        assertTrue("since=0 muss isFullReload setzen", response.isFullReload)
        assertTrue(response.maxSeq > 0)
    }

    @Test
    fun `gebuchte Zeile erscheint im Sync-Delta`(): Unit = runBlocking {
        val table = nextTable()
        val before = api.sync(since = 1).maxSeq

        val lineId = bookLine(table)

        val delta = api.sync(since = before)
        val line = delta.lines.singleOrNull { it.id == lineId }

        assertNotNull("Die gebuchte Zeile fehlt im Delta ab seq=$before", line)
        assertEquals(table, line!!.tableNumber)
        assertEquals(article.id, line.articleId)
        // Name und Preis friert der Server aus dem Katalog ein.
        assertEquals(article.name, line.nameSnapshot)
        assertEquals(article.priceCents, line.unitPriceCents)
        assertTrue("Frische Zeile muss offen sein", line.isOpen)
        assertFalse("isFullReload darf bei since>0 nicht gesetzt sein", delta.isFullReload)
    }

    @Test
    fun `dieselbe Zeile zweimal gebucht ergibt genau eine Zeile`(): Unit = runBlocking {
        val table = nextTable()
        val lineId = UUID.randomUUID()
        val before = api.sync(since = 1).maxSeq

        bookLine(table, id = lineId)
        bookLine(table, id = lineId)

        val matching = api.sync(since = before).lines.filter { it.id == lineId }
        assertEquals("Idempotenz verletzt: $matching", 1, matching.size)
    }

    /**
     * Der Vertrag fuer die Notiz: Feldname `note`, der Server trimmt. Sie reist
     * mit der Bestellzeile, weil nicht die App den Kuechenbon ausloest, sondern
     * der Druckdienst, der `/sync` pollt.
     */
    @Test
    fun `Notiz zur Position kommt getrimmt im Sync-Delta zurueck`(): Unit = runBlocking {
        val table = nextTable()
        val before = api.sync(since = 1).maxSeq

        val lineId = bookLine(table, note = "  ohne Senf  ")

        val line = api.sync(since = before).lines.single { it.id == lineId }
        assertEquals("ohne Senf", line.note)
    }

    /**
     * Fehlt der Schluessel im JSON, ist der Wert `null` — und nicht `""`. Sonst
     * waere „keine Notiz" von „leere Notiz" nicht zu unterscheiden.
     */
    @Test
    fun `Buchung ohne Notiz kommt ohne Notiz zurueck`(): Unit = runBlocking {
        val table = nextTable()
        val before = api.sync(since = 1).maxSeq

        val lineId = bookLine(table)

        val line = api.sync(since = before).lines.single { it.id == lineId }
        assertNull(line.note)
    }

    /**
     * Gekuerzt, nicht abgewiesen: ein 4xx wuerde die ganze Buchungsrunde
     * dauerhaft in der Offline-Queue parken, und der Gast bekaeme nichts.
     */
    @Test
    fun `zu lange Notiz wird still auf 120 Zeichen gekuerzt`(): Unit = runBlocking {
        val table = nextTable()
        val before = api.sync(since = 1).maxSeq

        val lineId = bookLine(table, note = "s".repeat(200))

        val line = api.sync(since = before).lines.single { it.id == lineId }
        assertEquals(120, line.note?.length)
    }

    // MARK: - Kassieren

    @Test
    fun `zweimal kassieren meldet einen typisierten Konflikt`(): Unit = runBlocking {
        val table = nextTable()
        val lineId = bookLine(table)
        val selection = listOf(SettlementLineSelection(lineId = lineId, qty = 1))

        val settlement = api.createSettlement(
            CreateSettlementRequest(
                id = UUID.randomUUID(),
                tableNumber = table,
                lines = selection,
                amountCents = article.priceCents,
                paidAt = KassaClock.now(),
            ),
        )
        assertEquals(article.priceCents, settlement.totalCents)

        // Neue Settlement-ID: dieselbe waere idempotent und kaeme ohne Konflikt zurueck.
        val error = assertThrows(ApiException.SettlementConflict::class.java) {
            runBlocking {
                api.createSettlement(
                    CreateSettlementRequest(
                        id = UUID.randomUUID(),
                        tableNumber = table,
                        lines = selection,
                        amountCents = article.priceCents,
                        paidAt = KassaClock.now(),
                    ),
                )
            }
        }

        assertEquals(SettlementConflictDto.Reason.ALREADY_SETTLED, error.conflict.reason)
        assertEquals(listOf(lineId), error.conflict.conflictingLineIds)
        assertEquals(DEVICE_NAME, error.conflict.settledByDevice)
        assertEquals(ApiException.Disposition.PERMANENT, error.disposition)
    }

    @Test
    fun `falscher Betrag meldet amountMismatch`(): Unit = runBlocking {
        val table = nextTable()
        val lineId = bookLine(table)

        val error = assertThrows(ApiException.SettlementConflict::class.java) {
            runBlocking {
                api.createSettlement(
                    CreateSettlementRequest(
                        id = UUID.randomUUID(),
                        tableNumber = table,
                        lines = listOf(SettlementLineSelection(lineId, 1)),
                        // Bewusst daneben — der Server rechnet selbst nach.
                        amountCents = article.priceCents + 13,
                        paidAt = KassaClock.now(),
                    ),
                )
            }
        }

        assertEquals(SettlementConflictDto.Reason.AMOUNT_MISMATCH, error.conflict.reason)
    }

    @Test
    fun `unbekannte Zeile meldet unknownLine`(): Unit = runBlocking {
        val error = assertThrows(ApiException.SettlementConflict::class.java) {
            runBlocking {
                api.createSettlement(
                    CreateSettlementRequest(
                        id = UUID.randomUUID(),
                        tableNumber = nextTable(),
                        lines = listOf(SettlementLineSelection(UUID.randomUUID(), 1)),
                        amountCents = 100,
                        paidAt = KassaClock.now(),
                    ),
                )
            }
        }

        assertEquals(SettlementConflictDto.Reason.UNKNOWN_LINE, error.conflict.reason)
    }

    /**
     * Der Grund, warum es [KassaClock] gibt: mit `Instant.now()` und seinen
     * Nanosekunden kaeme hier ein anderer Zeitstempel zurueck, als lokal steht.
     */
    @Test
    fun `mit KassaClock erzeugtes paidAt kommt bitgleich zurueck`(): Unit = runBlocking {
        val table = nextTable()
        val lineId = bookLine(table)
        val paidAt = KassaClock.now()

        val settlement = api.createSettlement(
            CreateSettlementRequest(
                id = UUID.randomUUID(),
                tableNumber = table,
                lines = listOf(SettlementLineSelection(lineId, 1)),
                amountCents = article.priceCents,
                paidAt = paidAt,
                tipCents = 50,
            ),
        )

        assertEquals(paidAt, settlement.paidAt)
        assertEquals(50, settlement.tipCents)
        assertEquals(article.priceCents, settlement.totalCents)
        // Trinkgeld steht neben dem Umsatz, nicht darin.
        assertEquals(article.priceCents + 50, settlement.grandTotal.cents)
        assertEquals(BusinessDay.day(paidAt, cutoffHour), settlement.businessDay)
    }

    /**
     * Der zweite Fehlerkoerper des Servers: Vapors `{"error":true,"reason":"..."}`.
     * Bei 409 kommt stattdessen ein nacktes SettlementConflictDto — dass beide
     * Formate am selben Client ankommen, ist genau die Stelle, an der ein
     * einzelner `body<T>()`-Aufruf den Kanal verbraucht haette.
     */
    @Test
    fun `leere Auswahl meldet einen Server-Fehler mit Grund`() {
        val error = assertThrows(ApiException.Server::class.java) {
            runBlocking {
                api.createSettlement(
                    CreateSettlementRequest(
                        id = UUID.randomUUID(),
                        tableNumber = nextTable(),
                        lines = emptyList(),
                        amountCents = 0,
                        paidAt = KassaClock.now(),
                    ),
                )
            }
        }

        assertEquals(400, error.status)
        assertEquals("Keine Zeilen ausgewaehlt", error.reason?.replace("ä", "ae"))
        assertEquals(ApiException.Disposition.PERMANENT, error.disposition)
    }

    @Test
    fun `negatives Trinkgeld wird abgewiesen`() {
        val error = assertThrows(ApiException.Server::class.java) {
            runBlocking {
                val table = nextTable()
                val lineId = bookLine(table)
                api.createSettlement(
                    CreateSettlementRequest(
                        id = UUID.randomUUID(),
                        tableNumber = table,
                        lines = listOf(SettlementLineSelection(lineId, 1)),
                        amountCents = article.priceCents,
                        paidAt = KassaClock.now(),
                        tipCents = -100,
                    ),
                )
            }
        }

        assertEquals(400, error.status)
        assertNotNull("Der Grund aus dem Vapor-Fehlerkoerper fehlt", error.reason)
    }

    // MARK: - Druckauftrag

    @Test
    fun `Druckauftrag kommt aufgeloest zurueck`(): Unit = runBlocking {
        val table = nextTable()
        val lineId = bookLine(table, qty = 2)

        val printRequest = api.createPrintRequest(
            CreatePrintRequestRequest(
                id = UUID.randomUUID(),
                tableNumber = table,
                lines = listOf(SettlementLineSelection(lineId, 2)),
                requestedAt = KassaClock.now(),
            ),
        )

        assertEquals(table, printRequest.tableNumber)
        val item = printRequest.items.single()
        // Namen und Preise loest der Server auf, der Client schickt sie nicht mit.
        assertEquals(article.name, item.name)
        assertEquals(2, item.qty)
        assertEquals(article.priceCents, item.unitPriceCents)
        assertEquals(article.priceCents * 2, printRequest.totalCents)
    }

    /**
     * Die Gegenrichtung pollt nur der Druckdienst, nie die App — deshalb gibt es
     * dafuer keine [at.heuriger.kassa.net.KassaApi]-Methode, und der
     * Vertragstest greift hier ausnahmsweise roh zu. Eine Methode nur fuer den
     * Test waere toter Code in der App.
     */
    private fun fetchDayReportPrintRequests(since: Int): List<DayReportPrintRequestDto> {
        val url = URL(
            "${ContractEnv.requireBaseUrl()}/${ApiRoute.DAY_REPORT_PRINT_REQUESTS}?since=$since",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            assertEquals(200, connection.responseCode)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return KassaJson.instance.decodeFromString(
                ListSerializer(DayReportPrintRequestDto.serializer()),
                body,
            )
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `Statistik-Druckauftrag kommt mit Betriebstag und Geraet zurueck`(): Unit = runBlocking {
        val businessDay = BusinessDay.day(KassaClock.now(), cutoffHour)

        val created = api.createDayReportPrintRequest(
            CreateDayReportPrintRequestRequest(
                id = UUID.randomUUID(),
                businessDay = businessDay,
                requestedAt = KassaClock.now(),
            ),
        )

        assertEquals(businessDay, created.businessDay)
        // Die Geraete-ID setzt der Server aus dem Token, der Client schickt keine.
        assertTrue("Geraete-ID darf nicht leer sein", created.deviceId.isNotBlank())
        assertTrue(created.updatedSeq > 0)
    }

    /** Ein zweimal gedrueckter Knopf darf keinen zweiten Zettel erzeugen. */
    @Test
    fun `derselbe Statistik-Druckauftrag zweimal geschickt aendert nichts`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val request = CreateDayReportPrintRequestRequest(
            id = id,
            businessDay = BusinessDay.day(KassaClock.now(), cutoffHour),
            requestedAt = KassaClock.now(),
        )

        val first = api.createDayReportPrintRequest(request)
        // Anderer Zeitstempel, dieselbe ID: der Server muss den alten Stand halten.
        val second = api.createDayReportPrintRequest(request.copy(requestedAt = KassaClock.now()))

        assertEquals(first, second)
        assertEquals(1, fetchDayReportPrintRequests(first.updatedSeq - 1).count { it.id == id })
    }

    @Test
    fun `Statistik-Druckauftrag erscheint im since-Delta`(): Unit = runBlocking {
        val businessDay = BusinessDay.day(KassaClock.now(), cutoffHour)
        val requestedAt = KassaClock.now()

        val created = api.createDayReportPrintRequest(
            CreateDayReportPrintRequestRequest(
                id = UUID.randomUUID(),
                businessDay = businessDay,
                requestedAt = requestedAt,
            ),
        )

        val found = fetchDayReportPrintRequests(created.updatedSeq - 1)
            .singleOrNull { it.id == created.id }

        assertNotNull("Der Auftrag fehlt im Delta ab seq=${created.updatedSeq - 1}", found)
        assertEquals(created, found)
        assertEquals(requestedAt, found!!.requestedAt)
        assertTrue(
            "since muss echt groesser filtern",
            fetchDayReportPrintRequests(created.updatedSeq).none { it.id == created.id },
        )
    }

    // MARK: - Tagesabschluss

    @Test
    fun `dayReport liefert einen Bericht fuer den laufenden Betriebstag`(): Unit = runBlocking {
        val today = BusinessDay.day(KassaClock.now(), cutoffHour)

        val report = api.dayReport(today)

        assertEquals(today, report.businessDay)
        assertEquals(report.settlements.size, report.settlementCount)
        // Die Kategoriesummen gehen exakt auf den Warenumsatz auf.
        assertEquals(report.totalCents, report.byCategory.sumOf { it.totalCents })
        assertEquals(report.settlements.sumOf { it.totalCents }, report.totalCents)
        assertEquals(report.settlements.sumOf { it.tipCents }, report.tipCents)
    }

    /**
     * Beweist, dass der Betriebstag wirklich uebertragen wird. Der Server liest
     * den Parameter `date` und faellt ohne ihn stillschweigend auf *heute*
     * zurueck — ein Test, der nur heute abfragt, wuerde einen falschen
     * Parameternamen also nie bemerken. Deshalb ein anderer Tag.
     */
    @Test
    fun `dayReport fragt den uebergebenen Betriebstag ab, nicht heute`(): Unit = runBlocking {
        val today = BusinessDay.day(KassaClock.now(), cutoffHour)
        val yesterday = java.time.LocalDate.parse(today).minusDays(1).toString()

        val report = api.dayReport(yesterday)

        assertEquals(yesterday, report.businessDay)
        assertTrue(
            "Ein Bericht fuer $yesterday darf keine Zahlung von $today enthalten",
            report.settlements.all { it.businessDay == yesterday },
        )
    }

    // MARK: - WebSocket

    @Test
    fun `WebSocket meldet eine Buchung als Ping`(): Unit = runBlocking {
        val received = CompletableDeferred<SyncPing>()

        val collector = launch {
            api.syncPings().collect { ping ->
                if (!received.isCompleted) received.complete(ping)
            }
        }

        try {
            val seqBefore = api.sync(since = 1).maxSeq
            // Dem Handshake Zeit geben; ein Ping vor dem Verbinden ginge verloren.
            delay(750)

            bookLine(nextTable())

            val ping = withTimeout(15_000) { received.await() }
            assertTrue(
                "Ping-seq ${ping.seq} muss nach der Buchung groesser als $seqBefore sein",
                ping.seq > seqBefore,
            )
        } finally {
            collector.cancel()
        }
    }
}
