package at.heuriger.kassa.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import at.heuriger.kassa.wire.ArticleDto
import at.heuriger.kassa.wire.OrderLineDto
import at.heuriger.kassa.wire.SettlementDto
import java.time.Instant
import java.util.UUID

/**
 * Der lokale Spiegel. Felder eins zu eins wie in `App/Sources/Model/LocalModels.swift`.
 *
 * Bewusst ohne Fremdschluessel: iOS referenziert ebenfalls nur ueber IDs. Ein
 * harter FK auf `articleId` wuerde den Merge blockieren, sobald eine Zeile
 * eintrifft, deren Artikel der Katalog noch nicht kennt.
 */
@Entity(tableName = "articles")
data class ArticleEntity(
    /** Achtung: String, keine UUID — der Katalog kommt aus einem CSV-Import. */
    @PrimaryKey val id: String,
    val category: String,
    val name: String,
    @ColumnInfo(name = "price_cents") val priceCents: Int,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val active: Boolean,
) {
    /** Spiegel von `LocalArticle.apply(_:)` — `id` bleibt unangetastet. */
    fun applying(dto: ArticleDto): ArticleEntity = copy(
        category = dto.category,
        name = dto.name,
        priceCents = dto.priceCents,
        sortOrder = dto.sortOrder,
        active = dto.active,
    )

    companion object {
        fun from(dto: ArticleDto): ArticleEntity = ArticleEntity(
            id = dto.id,
            category = dto.category,
            name = dto.name,
            priceCents = dto.priceCents,
            sortOrder = dto.sortOrder,
            active = dto.active,
        )
    }
}

@Entity(
    tableName = "order_lines",
    indices = [Index("table_number"), Index("created_at")],
)
data class OrderLineEntity(
    @PrimaryKey val id: UUID,
    @ColumnInfo(name = "table_number") val tableNumber: Int,
    @ColumnInfo(name = "article_id") val articleId: String,
    /**
     * Name und Preis werden bei der Buchung eingefroren: eine spaetere
     * Preisaenderung darf alte Rechnungen nicht rueckwirkend veraendern.
     */
    @ColumnInfo(name = "name_snapshot") val nameSnapshot: String,
    @ColumnInfo(name = "unit_price_cents") val unitPriceCents: Int,
    val qty: Int,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "voided_at") val voidedAt: Instant? = null,
    @ColumnInfo(name = "settlement_id") val settlementId: UUID? = null,
    @ColumnInfo(name = "updated_seq", defaultValue = "0") val updatedSeq: Int = 0,
    /**
     * Sonderwunsch zu dieser Position. Bewusst ohne `defaultValue`: eine Zeile
     * ohne Sonderwunsch hat keine Notiz, und `''` waere davon nicht zu
     * unterscheiden.
     */
    @ColumnInfo(name = "note") val note: String? = null,
    /**
     * Optimistisch lokal angelegt und vom Server noch nicht bestaetigt. Solche
     * Zeilen ueberleben einen Full Reload, sonst verschwaende die Buchung,
     * bevor die Queue sie ueberhaupt abgesetzt hat.
     */
    @ColumnInfo(name = "pending_local") val pendingLocal: Boolean = true,
) {
    val isOpen: Boolean get() = voidedAt == null && settlementId == null

    /** Spiegel von `LocalOrderLine.apply(_:)`: der Serverstand gewinnt vollstaendig. */
    fun applying(dto: OrderLineDto): OrderLineEntity = copy(
        tableNumber = dto.tableNumber,
        articleId = dto.articleId,
        nameSnapshot = dto.nameSnapshot,
        unitPriceCents = dto.unitPriceCents,
        qty = dto.qty,
        createdAt = dto.createdAt,
        deviceId = dto.deviceId,
        voidedAt = dto.voidedAt,
        settlementId = dto.settlementId,
        updatedSeq = dto.updatedSeq,
        note = dto.note,
        pendingLocal = false,
    )

    companion object {
        fun from(dto: OrderLineDto): OrderLineEntity = OrderLineEntity(
            id = dto.id,
            tableNumber = dto.tableNumber,
            articleId = dto.articleId,
            nameSnapshot = dto.nameSnapshot,
            unitPriceCents = dto.unitPriceCents,
            qty = dto.qty,
            createdAt = dto.createdAt,
            deviceId = dto.deviceId,
            voidedAt = dto.voidedAt,
            settlementId = dto.settlementId,
            updatedSeq = dto.updatedSeq,
            note = dto.note,
            pendingLocal = false,
        )
    }
}

@Entity(tableName = "settlements", indices = [Index("paid_at")])
data class SettlementEntity(
    @PrimaryKey val id: UUID,
    @ColumnInfo(name = "table_number") val tableNumber: Int,
    @ColumnInfo(name = "total_cents") val totalCents: Int,
    @ColumnInfo(name = "paid_at") val paidAt: Instant,
    @ColumnInfo(name = "device_id") val deviceId: String,
    /** Betriebstag als `yyyy-MM-dd` — nicht der Kalendertag, siehe Cutoff. */
    @ColumnInfo(name = "business_day") val businessDay: String,
    @ColumnInfo(name = "updated_seq", defaultValue = "0") val updatedSeq: Int = 0,
    @ColumnInfo(name = "pending_local") val pendingLocal: Boolean = true,
    /** Getrennt vom Umsatz, damit `totalCents` weiter gegen die Zeilen aufgeht. */
    @ColumnInfo(name = "tip_cents", defaultValue = "0") val tipCents: Int = 0,
) {
    fun applying(dto: SettlementDto): SettlementEntity = copy(
        tableNumber = dto.tableNumber,
        totalCents = dto.totalCents,
        paidAt = dto.paidAt,
        deviceId = dto.deviceId,
        businessDay = dto.businessDay,
        updatedSeq = dto.updatedSeq,
        pendingLocal = false,
        tipCents = dto.tipCents,
    )

    companion object {
        fun from(dto: SettlementDto): SettlementEntity = SettlementEntity(
            id = dto.id,
            tableNumber = dto.tableNumber,
            totalCents = dto.totalCents,
            paidAt = dto.paidAt,
            deviceId = dto.deviceId,
            businessDay = dto.businessDay,
            updatedSeq = dto.updatedSeq,
            pendingLocal = false,
            tipCents = dto.tipCents,
        )
    }
}

/**
 * Offline-Queue.
 *
 * **Abweichung von iOS, und zwar eine notwendige:** dort sortiert die Queue nach
 * `createdAt`. Swifts `Date` ist ein Double, Gleichstaende sind praktisch
 * ausgeschlossen. Javas `Instant` liegt hier auf Sekunden — und `changeQty` legt
 * in derselben Sekunde erst `voidLine` und dann `addLines` an. Nach `createdAt`
 * waere die FIFO-Reihenfolge damit nichtdeterministisch und der Server bekaeme
 * die Neubuchung womoeglich vor dem Storno.
 *
 * Deshalb ist [orderIndex] der Primaerschluessel (`INTEGER PRIMARY KEY
 * AUTOINCREMENT`, also streng monoton und nie wiederverwendet) und die einzige
 * Sortierung der Queue. [id] bleibt fachlicher Schluessel und ist eindeutig
 * indiziert; [createdAt] dient nur noch der Anzeige.
 */
@Entity(tableName = "pending_commands", indices = [Index(value = ["id"], unique = true)])
data class PendingCommandEntity(
    @ColumnInfo(name = "id") val id: UUID,
    @ColumnInfo(name = "kind_raw") val kindRaw: String,
    val payload: String,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    /**
     * Ein 4xx wird sich nicht von selbst heilen. Solche Commands bleiben zur
     * Ansicht in den Einstellungen liegen, blockieren aber die FIFO nicht.
     */
    @ColumnInfo(name = "failed_permanently", defaultValue = "0") val failedPermanently: Boolean = false,
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "order_index") val orderIndex: Long = 0,
)
