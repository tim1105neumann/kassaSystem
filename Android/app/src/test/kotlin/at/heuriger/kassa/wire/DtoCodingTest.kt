package at.heuriger.kassa.wire

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spiegelt `Shared/Tests/KassaSharedTests/DTOCodingTests.swift`. */
class DtoCodingTest {

    private val json = KassaJson.instance

    /** Derselbe Zeitpunkt wie im Swift-Test: 2026-05-28T20:26:40Z. */
    private val referenceInstant: Instant = Instant.ofEpochSecond(1_780_000_000)
    private val referenceIso = "2026-05-28T20:26:40Z"

    @Test
    fun `Bestellzeile ueberlebt einen JSON-Rundlauf unveraendert`() {
        val original = OrderLineDto(
            id = UUID.randomUUID(),
            tableNumber = 12,
            articleId = "abc123",
            nameSnapshot = "Käsekrainer mit Pommes",
            unitPriceCents = 920,
            qty = 3,
            createdAt = referenceInstant,
            deviceId = "device-a",
            updatedSeq = 42,
        )
        val encoded = json.encodeToString(OrderLineDto.serializer(), original)
        val decoded = json.decodeFromString(OrderLineDto.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals(2760, decoded.lineTotal.cents)
        assertTrue(decoded.isOpen)
    }

    @Test
    fun `kassierte oder stornierte Zeile gilt nicht mehr als offen`() {
        val line = OrderLineDto(
            id = UUID.randomUUID(),
            tableNumber = 1,
            articleId = "a",
            nameSnapshot = "Bier 0,3 l",
            unitPriceCents = 380,
            qty = 1,
            createdAt = referenceInstant,
            deviceId = "d",
            updatedSeq = 1,
        )
        assertTrue(line.isOpen)
        assertFalse(line.copy(settlementId = UUID.randomUUID()).isOpen)
        assertFalse(line.copy(voidedAt = referenceInstant).isOpen)
    }

    /**
     * Swifts synthetisierter Encoder nutzt `encodeIfPresent` und laesst `nil`-Felder
     * komplett weg. Ein `"voidedAt": null` wuerde der Server als Feld sehen, das es
     * so nie gibt — deshalb `explicitNulls = false`.
     */
    @Test
    fun `nicht gesetzte Optionals erscheinen gar nicht im JSON`() {
        val line = OrderLineDto(
            id = UUID.randomUUID(),
            tableNumber = 1,
            articleId = "a",
            nameSnapshot = "Bier 0,3 l",
            unitPriceCents = 380,
            qty = 1,
            createdAt = referenceInstant,
            deviceId = "d",
            voidedAt = null,
            settlementId = null,
            updatedSeq = 1,
        )
        val encoded = json.encodeToString(OrderLineDto.serializer(), line)

        assertFalse(encoded.contains("voidedAt"))
        assertFalse(encoded.contains("settlementId"))
        assertFalse(encoded.contains("null"))
    }

    @Test
    fun `gesetzte Optionals erscheinen sehr wohl`() {
        val settlementId = UUID.randomUUID()
        val line = OrderLineDto(
            id = UUID.randomUUID(),
            tableNumber = 1,
            articleId = "a",
            nameSnapshot = "Bier 0,3 l",
            unitPriceCents = 380,
            qty = 1,
            createdAt = referenceInstant,
            deviceId = "d",
            voidedAt = referenceInstant,
            settlementId = settlementId,
            updatedSeq = 1,
        )
        val encoded = json.encodeToString(OrderLineDto.serializer(), line)

        assertTrue(encoded.contains("\"voidedAt\":\"$referenceIso\""))
        assertTrue(encoded.contains(settlementId.toString()))
        assertEquals(line, json.decodeFromString(OrderLineDto.serializer(), encoded))
    }

    @Test
    fun `Trinkgeld ueberlebt den Rundlauf und zaehlt nicht zum Umsatz`() {
        val original = SettlementDto(
            id = UUID.randomUUID(),
            tableNumber = 4,
            totalCents = 2970,
            paidAt = referenceInstant,
            deviceId = "device-a",
            businessDay = "2026-09-05",
            updatedSeq = 7,
            tipCents = 130,
        )
        val decoded = json.decodeFromString(
            SettlementDto.serializer(),
            json.encodeToString(SettlementDto.serializer(), original),
        )

        assertEquals(original, decoded)
        assertEquals(2970, decoded.total.cents)
        assertEquals(130, decoded.tip.cents)
        assertEquals(3100, decoded.grandTotal.cents)
    }

    /** `encodeDefaults = true`: der Server darf `tipCents` nie vermissen. */
    @Test
    fun `tipCents wird auch als 0 mitgesendet`() {
        val settlement = SettlementDto(
            id = UUID.randomUUID(),
            tableNumber = 4,
            totalCents = 2970,
            paidAt = referenceInstant,
            deviceId = "device-a",
            businessDay = "2026-09-05",
            updatedSeq = 7,
        )
        assertTrue(
            json.encodeToString(SettlementDto.serializer(), settlement)
                .contains("\"tipCents\":0")
        )

        val request = CreateSettlementRequest(
            id = UUID.randomUUID(),
            tableNumber = 3,
            lines = emptyList(),
            amountCents = 1240,
            paidAt = referenceInstant,
        )
        assertTrue(
            json.encodeToString(CreateSettlementRequest.serializer(), request)
                .contains("\"tipCents\":0")
        )
    }

    /**
     * Der Fall, der sonst still bricht: die Offline-Queue haelt alte Payloads
     * als rohes JSON, die kennen `tipCents` noch nicht.
     */
    @Test
    fun `ein Kassiervorgang ohne tipCents dekodiert zu 0`() {
        val raw = """
        {
          "id": "6A3D2E64-1C1C-4C0E-9B2C-1F7E9E2A1B01",
          "tableNumber": 3,
          "lines": [{"lineId": "6A3D2E64-1C1C-4C0E-9B2C-1F7E9E2A1B02", "qty": 2}],
          "amountCents": 1240,
          "paidAt": "2026-09-05T21:00:00Z"
        }
        """.trimIndent()

        val decoded = json.decodeFromString(CreateSettlementRequest.serializer(), raw)

        assertEquals(1240, decoded.amountCents)
        assertEquals(0, decoded.tipCents)
        assertEquals(2, decoded.lines.single().qty)
    }

    @Test
    fun `eine Zahlung ohne tipCents dekodiert zu 0`() {
        val raw = """
        {
          "id": "6A3D2E64-1C1C-4C0E-9B2C-1F7E9E2A1B01",
          "tableNumber": 4,
          "totalCents": 2970,
          "paidAt": "2026-09-05T21:00:00Z",
          "deviceId": "device-a",
          "businessDay": "2026-09-05",
          "updatedSeq": 7
        }
        """.trimIndent()

        assertEquals(0, json.decodeFromString(SettlementDto.serializer(), raw).tipCents)
    }

    @Test
    fun `ein Tagesbericht ohne tipCents dekodiert zu 0`() {
        val raw = """
        {
          "businessDay": "2026-09-05",
          "totalCents": 2970,
          "settlementCount": 1,
          "byCategory": [],
          "topArticles": [],
          "settlements": []
        }
        """.trimIndent()

        val decoded = json.decodeFromString(DayReportDto.serializer(), raw)

        assertEquals(0, decoded.tipCents)
        assertEquals(2970, decoded.grandTotal.cents)
    }

    @Test
    fun `Druckauftrag-Anforderung ueberlebt einen JSON-Rundlauf unveraendert`() {
        val lineId = UUID.randomUUID()
        val original = CreatePrintRequestRequest(
            id = UUID.randomUUID(),
            tableNumber = 9,
            lines = listOf(SettlementLineSelection(lineId = lineId, qty = 2)),
            requestedAt = referenceInstant,
        )
        val encoded = json.encodeToString(CreatePrintRequestRequest.serializer(), original)

        assertTrue(encoded.contains(referenceIso))

        val decoded = json.decodeFromString(CreatePrintRequestRequest.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals(listOf(SettlementLineSelection(lineId, 2)), decoded.lines)
    }

    @Test
    fun `Druckauftrag ueberlebt den Rundlauf samt verschachtelter Positionen`() {
        val original = PrintRequestDto(
            id = UUID.randomUUID(),
            tableNumber = 9,
            items = listOf(
                PrintRequestDto.Item(name = "Käsekrainer mit Pommes", qty = 2, unitPriceCents = 920),
                PrintRequestDto.Item(name = "Bier 0,3 l", qty = 3, unitPriceCents = 380),
            ),
            totalCents = 2980,
            requestedAt = referenceInstant,
            deviceId = "device-a",
            updatedSeq = 13,
        )
        val encoded = json.encodeToString(PrintRequestDto.serializer(), original)

        assertTrue(encoded.contains(referenceIso))

        val decoded = json.decodeFromString(PrintRequestDto.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals(2, decoded.items.size)
        assertEquals(1840, decoded.items[0].lineTotal.cents)
        assertEquals(1140, decoded.items[1].lineTotal.cents)
        assertEquals(2980, decoded.total.cents)
    }

    @Test
    fun `Statistik-Druckauftrag ueberlebt einen JSON-Rundlauf unveraendert`() {
        val original = CreateDayReportPrintRequestRequest(
            id = UUID.randomUUID(),
            businessDay = "2026-05-28",
            requestedAt = referenceInstant,
        )
        val encoded = json.encodeToString(CreateDayReportPrintRequestRequest.serializer(), original)

        // Die Swift-Schreibweise ist Vertrag: `businessDay`, ISO-8601 in UTC.
        assertTrue(encoded.contains("\"businessDay\":\"2026-05-28\""))
        assertTrue(encoded.contains(referenceIso))

        val decoded = json.decodeFromString(CreateDayReportPrintRequestRequest.serializer(), encoded)
        assertEquals(original, decoded)
    }

    /** Traegt keine Zahlen — der Druckdienst holt den Bericht selbst. */
    @Test
    fun `angelegter Statistik-Druckauftrag ueberlebt den Rundlauf`() {
        val original = DayReportPrintRequestDto(
            id = UUID.randomUUID(),
            businessDay = "2026-05-28",
            requestedAt = referenceInstant,
            deviceId = "device-a",
            updatedSeq = 17,
        )
        val encoded = json.encodeToString(DayReportPrintRequestDto.serializer(), original)

        assertTrue(encoded.contains("\"deviceId\":\"device-a\""))
        assertTrue(encoded.contains("\"updatedSeq\":17"))
        assertTrue(encoded.contains(referenceIso))

        val decoded = json.decodeFromString(DayReportPrintRequestDto.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `Konfliktantwort transportiert die betroffenen Zeilen`() {
        val id = UUID.randomUUID()
        val original = SettlementConflictDto(
            conflictingLineIds = listOf(id),
            settledByDevice = "iPhone Sepp",
            reason = SettlementConflictDto.Reason.ALREADY_SETTLED,
        )
        val encoded = json.encodeToString(SettlementConflictDto.serializer(), original)
        val decoded = json.decodeFromString(SettlementConflictDto.serializer(), encoded)

        assertEquals(listOf(id), decoded.conflictingLineIds)
        assertEquals(SettlementConflictDto.Reason.ALREADY_SETTLED, decoded.reason)
        assertEquals("iPhone Sepp", decoded.settledByDevice)
    }

    /** Die Rohwerte sind Vertrag — Kotlins Enum-Namen duerfen nicht durchschlagen. */
    @Test
    fun `Konfliktgruende behalten ihre Swift-Rohwerte`() {
        val expected = mapOf(
            SettlementConflictDto.Reason.ALREADY_SETTLED to "alreadySettled",
            SettlementConflictDto.Reason.LINE_VOIDED to "lineVoided",
            SettlementConflictDto.Reason.AMOUNT_MISMATCH to "amountMismatch",
            SettlementConflictDto.Reason.UNKNOWN_LINE to "unknownLine",
            SettlementConflictDto.Reason.QUANTITY_EXCEEDED to "quantityExceeded",
        )
        for ((reason, raw) in expected) {
            val conflict = SettlementConflictDto(emptyList(), null, reason)
            val encoded = json.encodeToString(SettlementConflictDto.serializer(), conflict)
            assertTrue("$raw fehlt in $encoded", encoded.contains("\"reason\":\"$raw\""))
            assertEquals(
                reason,
                json.decodeFromString(SettlementConflictDto.serializer(), encoded).reason,
            )
        }
    }

    @Test
    fun `fehlendes settledByDevice bleibt weg und dekodiert zu null`() {
        val conflict = SettlementConflictDto(
            conflictingLineIds = emptyList(),
            settledByDevice = null,
            reason = SettlementConflictDto.Reason.AMOUNT_MISMATCH,
        )
        val encoded = json.encodeToString(SettlementConflictDto.serializer(), conflict)

        assertFalse(encoded.contains("settledByDevice"))
        assertEquals(null, json.decodeFromString(SettlementConflictDto.serializer(), encoded).settledByDevice)
    }

    // MARK: - Zeitstempel

    /**
     * Der harte Vertrag: ISO-8601 in UTC, ohne Sekundenbruchteile. Javas
     * `Instant.toString()` haengt sonst Millisekunden an.
     */
    @Test
    fun `Zeitstempel gehen ohne Sekundenbruchteile auf die Leitung`() {
        val request = CreatePrintRequestRequest(
            id = UUID.randomUUID(),
            tableNumber = 9,
            lines = emptyList(),
            requestedAt = Instant.ofEpochSecond(1_780_000_000, 123_000_000),
        )
        val encoded = json.encodeToString(CreatePrintRequestRequest.serializer(), request)

        assertTrue(encoded.contains("\"requestedAt\":\"$referenceIso\""))
        assertFalse(encoded.contains(".123"))
    }

    /**
     * Der Server sendet keine Bruchteile, nimmt sie aber an und wirft sie weg.
     * Robustheit kostet hier nichts: wir lesen sie und reduzieren auf Sekunden,
     * damit der lokale Wert bitgleich zu dem bleibt, was zurueckkommt.
     */
    @Test
    fun `Dekodierung akzeptiert Bruchteile und reduziert sie auf Sekunden`() {
        val raw = """{"seq":1,"requestedAt":"2026-09-12T10:30:00.123Z"}"""
        val decoded = json.decodeFromString(TimestampProbe.serializer(), raw)

        assertEquals(Instant.parse("2026-09-12T10:30:00Z"), decoded.requestedAt)
        assertEquals(
            "2026-09-12T10:30:00Z",
            json.encodeToString(TimestampProbe.serializer(), decoded)
                .substringAfter("\"requestedAt\":\"").substringBefore("\""),
        )
    }

    @Test
    fun `Dekodierung akzeptiert andere Zonen-Offsets`() {
        val raw = """{"seq":1,"requestedAt":"2026-09-12T12:30:00+02:00"}"""
        val decoded = json.decodeFromString(TimestampProbe.serializer(), raw)

        assertEquals(Instant.parse("2026-09-12T10:30:00Z"), decoded.requestedAt)
    }

    @Test
    fun `unbekannte Felder brechen die Dekodierung nicht`() {
        val raw = """
        {
          "id": "6A3D2E64-1C1C-4C0E-9B2C-1F7E9E2A1B01",
          "name": "iPhone Sepp",
          "firmwareVersion": "brandneu"
        }
        """.trimIndent()

        assertEquals("iPhone Sepp", json.decodeFromString(DeviceDto.serializer(), raw).name)
    }

    /** ArticleDto.id und DeviceDto.id sind Strings, keine UUIDs. */
    @Test
    fun `Katalog-IDs sind freie Strings`() {
        val raw = """
        {
          "id": "wein-gruener-veltliner",
          "category": "Wein",
          "name": "Grüner Veltliner 1/8",
          "priceCents": 320,
          "sortOrder": 3,
          "active": true
        }
        """.trimIndent()

        val article = json.decodeFromString(ArticleDto.serializer(), raw)
        assertEquals("wein-gruener-veltliner", article.id)
        assertEquals(320, article.price.cents)
    }

    @Test
    fun `Sync-Antwort transportiert das Vollstaendig-neu-laden-Signal`() {
        val response = SyncResponse(
            lines = emptyList(),
            settlements = emptyList(),
            maxSeq = 17,
        )
        val encoded = json.encodeToString(SyncResponse.serializer(), response)

        assertTrue(encoded.contains("\"isFullReload\":false"))
        assertEquals(response, json.decodeFromString(SyncResponse.serializer(), encoded))
    }

    @Test
    fun `Routen entsprechen dem Swift-Vertrag`() {
        assertEquals("auth/login", ApiRoute.LOGIN)
        assertEquals("orders/lines", ApiRoute.ORDER_LINES)
        assertEquals("reports/day", ApiRoute.DAY_REPORT)

        val id = UUID.fromString("6a3d2e64-1c1c-4c0e-9b2c-1f7e9e2a1b01")
        assertEquals("orders/lines/6a3d2e64-1c1c-4c0e-9b2c-1f7e9e2a1b01/void", ApiRoute.voidLine(id))
    }

    /** Kleines Hilfs-DTO nur fuer die Zeitstempel-Toleranz. */
    @kotlinx.serialization.Serializable
    data class TimestampProbe(
        val seq: Int,
        @kotlinx.serialization.Serializable(with = InstantSerializer::class)
        val requestedAt: Instant,
    )
}
