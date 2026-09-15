@file:UseSerializers(InstantSerializer::class, UuidSerializer::class)

package at.heuriger.kassa.wire

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// MARK: - Katalog

@Serializable
data class ArticleDto(
    /** Achtung: String, keine UUID — der Katalog kommt aus einem CSV-Import. */
    val id: String,
    val category: String,
    val name: String,
    val priceCents: Int,
    val sortOrder: Int,
    val active: Boolean,
) {
    val price: Money get() = Money(priceCents)
}

// MARK: - Bestellungen

@Serializable
data class OrderLineDto(
    val id: UUID,
    val tableNumber: Int,
    val articleId: String,
    /**
     * Name und Preis werden bei der Buchung eingefroren: eine spaetere
     * Preisaenderung darf alte Rechnungen nicht rueckwirkend veraendern.
     */
    val nameSnapshot: String,
    val unitPriceCents: Int,
    val qty: Int,
    val createdAt: Instant,
    val deviceId: String,
    val voidedAt: Instant? = null,
    val settlementId: UUID? = null,
    val updatedSeq: Int,
    /**
     * Sonderwunsch des Gastes zu genau dieser Position. Der Server trimmt ihn und
     * kuerzt still auf 120 Zeichen — ein 4xx wuerde die ganze Buchungsrunde
     * dauerhaft in der Offline-Queue parken.
     */
    val note: String? = null,
) {
    val lineTotal: Money get() = Money(unitPriceCents * qty)

    /** Offen = weder storniert noch kassiert. */
    val isOpen: Boolean get() = voidedAt == null && settlementId == null
}

@Serializable
data class NewOrderLine(
    /**
     * Vom Client vergeben — macht das Anlegen idempotent, auch wenn die
     * Offline-Queue denselben Command mehrfach schickt.
     */
    val id: UUID,
    val tableNumber: Int,
    val articleId: String,
    val qty: Int,
    val createdAt: Instant,
    /**
     * Die Notiz haengt an der Bestellzeile, nicht am Bon: den Kuechenbon loest
     * nicht die App aus, sondern der Druckdienst, der `/sync` pollt und selbst
     * entscheidet, welche neuen Zeilen ein Bon werden.
     *
     * Default `null`: die Offline-Queue haelt alte Payloads als rohes JSON, die
     * kennen `note` noch nicht und muessen weiter lesbar bleiben.
     */
    val note: String? = null,
)

@Serializable
data class CreateOrderLinesRequest(
    val lines: List<NewOrderLine>,
)

// MARK: - Kassieren

@Serializable
data class SettlementLineSelection(
    val lineId: UUID,
    /** Teilmenge: kleiner als `qty` der Zeile ist erlaubt, der Server splittet dann. */
    val qty: Int,
)

@Serializable
data class CreateSettlementRequest(
    val id: UUID,
    val tableNumber: Int,
    val lines: List<SettlementLineSelection>,
    /**
     * Vom Client errechnete Summe. Der Server rechnet selbst nach und lehnt
     * bei Abweichung ab — schuetzt vor veraltetem Client-Stand.
     */
    val amountCents: Int,
    val paidAt: Instant,
    /**
     * Aufrundung des Gastes. Steht bewusst neben `amountCents` und nicht darin,
     * damit die Nachrechnung am Server weiterhin die reine Zeilensumme prueft.
     *
     * Default 0: die Offline-Queue haelt alte Payloads als rohes JSON, die
     * kennen `tipCents` noch nicht und muessen weiter lesbar bleiben.
     */
    val tipCents: Int = 0,
)

@Serializable
data class SettlementDto(
    val id: UUID,
    val tableNumber: Int,
    val totalCents: Int,
    val paidAt: Instant,
    val deviceId: String,
    /** Betriebstag als `yyyy-MM-dd` — nicht der Kalendertag, siehe Cutoff. */
    val businessDay: String,
    val updatedSeq: Int,
    /** Getrennt vom Umsatz, damit `totalCents` weiter gegen die Zeilen aufgeht. */
    val tipCents: Int = 0,
) {
    val total: Money get() = Money(totalCents)
    val tip: Money get() = Money(tipCents)

    /** Umsatz + Trinkgeld — was tatsaechlich in der Kassa liegt. */
    val grandTotal: Money get() = Money(totalCents + tipCents)
}

/** Antwortkoerper bei HTTP 409 auf `POST /settlements`. */
@Serializable
data class SettlementConflictDto(
    val conflictingLineIds: List<UUID>,
    val settledByDevice: String? = null,
    val reason: Reason,
) {
    @Serializable
    enum class Reason {
        @SerialName("alreadySettled") ALREADY_SETTLED,
        @SerialName("lineVoided") LINE_VOIDED,
        @SerialName("amountMismatch") AMOUNT_MISMATCH,
        @SerialName("unknownLine") UNKNOWN_LINE,
        @SerialName("quantityExceeded") QUANTITY_EXCEEDED,
    }
}

// MARK: - Druckauftrag

@Serializable
data class CreatePrintRequestRequest(
    /**
     * Vom Client vergeben — macht das Anlegen idempotent, auch wenn der Kellner
     * den Knopf zweimal drueckt oder die Offline-Queue nachliefert.
     */
    val id: UUID,
    val tableNumber: Int,
    /**
     * Dieselbe Auswahl wie beim Kassieren — die Aufstellung zeigt genau das,
     * was gleich bezahlt wuerde.
     */
    val lines: List<SettlementLineSelection>,
    val requestedAt: Instant,
)

/**
 * Was der Druckdienst bekommt. Ausdruecklich kein Beleg, sondern nur eine
 * Aufstellung zum Nachrechnen — deshalb ohne Betriebstag und Steuerfelder.
 */
@Serializable
data class PrintRequestDto(
    val id: UUID,
    val tableNumber: Int,
    val items: List<Item>,
    val totalCents: Int,
    val requestedAt: Instant,
    val deviceId: String,
    val updatedSeq: Int,
) {
    /**
     * Name und Preis loest der Server aus den Zeilen auf und friert sie hier ein,
     * wie bei `OrderLineDto.nameSnapshot`: der Ausdruck muss zeigen, was gebucht
     * wurde, nicht was der Katalog heute sagt.
     */
    @Serializable
    data class Item(
        val name: String,
        val qty: Int,
        val unitPriceCents: Int,
    ) {
        val lineTotal: Money get() = Money(unitPriceCents * qty)
    }

    val total: Money get() = Money(totalCents)
}

// MARK: - Druckauftrag Tagesstatistik

@Serializable
data class CreateDayReportPrintRequestRequest(
    /**
     * Vom Client vergeben, idempotent wie ueberall — ein zweimal gedrueckter
     * Knopf darf keinen zweiten Zettel erzeugen.
     */
    val id: UUID,
    /**
     * "yyyy-MM-dd", der im DatePicker gewaehlte Betriebstag. Gedruckt wird, was
     * am Bildschirm steht, nicht zwingend der heutige Tag.
     */
    val businessDay: String,
    val requestedAt: Instant,
)

/**
 * Traegt bewusst keine Zahlen. Der Bericht wird nicht eingefroren, sondern vom
 * Druckdienst ueber `GET /reports/day` frisch geholt — ein gespeicherter
 * Umsatz-Schnappschuss waere eine zweite Wahrheit neben der berechneten und
 * kaeme einem Journal naeher, als dieses System je sein will.
 */
@Serializable
data class DayReportPrintRequestDto(
    val id: UUID,
    val businessDay: String,
    val requestedAt: Instant,
    val deviceId: String,
    val updatedSeq: Int,
)

// MARK: - Sync

@Serializable
data class SyncResponse(
    val lines: List<OrderLineDto>,
    val settlements: List<SettlementDto>,
    val maxSeq: Int,
    /** Signalisiert, dass `since` zu alt war und der Client komplett neu laden muss. */
    val isFullReload: Boolean = false,
)

/**
 * Einzige Nachricht ueber den WebSocket. Er traegt bewusst keinen State, sondern
 * sagt nur "es gibt Neues" — der Client holt sich das Delta dann ueber /sync.
 */
@Serializable
data class SyncPing(
    val seq: Int,
)

// MARK: - Auth & Konfiguration

@Serializable
data class LoginRequest(
    val password: String,
    val deviceName: String,
)

@Serializable
data class LoginResponse(
    val token: String,
    val deviceId: String,
)

@Serializable
data class ServerConfigDto(
    val tableCount: Int,
    /** Ab welcher Stunde ein neuer Betriebstag beginnt (Default 6). */
    val businessDayCutoffHour: Int,
    /** Aendert sich bei jedem CSV-Import — der Client laedt den Katalog dann neu. */
    val catalogVersion: Int,
)

/**
 * Geraetename fuer die Anzeige — der Kuechenbon zeigt damit, wer bestellt hat.
 * Bewusst ohne Token-Hash: der verlaesst den Server nie.
 */
@Serializable
data class DeviceDto(
    /** Achtung: String, keine UUID. */
    val id: String,
    val name: String,
)

// MARK: - Tagesabschluss

@Serializable
data class DayReportDto(
    val businessDay: String,
    /** Reiner Warenumsatz — die Kategoriesummen gehen exakt darauf auf. */
    val totalCents: Int,
    val settlementCount: Int,
    val byCategory: List<CategoryTotal>,
    val topArticles: List<ArticleTotal>,
    val settlements: List<SettlementDto>,
    /** Default 0: ein Bericht von einem Server ohne Trinkgeld-Feld bleibt lesbar. */
    val tipCents: Int = 0,
) {
    @Serializable
    data class CategoryTotal(
        val category: String,
        val totalCents: Int,
    )

    @Serializable
    data class ArticleTotal(
        val articleId: String,
        val name: String,
        val qty: Int,
        val totalCents: Int,
    )

    val total: Money get() = Money(totalCents)
    val tip: Money get() = Money(tipCents)

    /** Umsatz + Trinkgeld — was tatsaechlich in der Kassa liegt. */
    val grandTotal: Money get() = Money(totalCents + tipCents)
}

// MARK: - Fehler

@Serializable
data class ApiErrorDto(
    val reason: String,
)
