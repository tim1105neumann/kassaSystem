package at.heuriger.kassa.data

import at.heuriger.kassa.data.db.ArticleEntity
import at.heuriger.kassa.data.db.OrderLineEntity
import at.heuriger.kassa.data.db.PendingCommandEntity
import at.heuriger.kassa.domain.OrderLine

/** Room-Entity -> Domaenen-Typ, damit `TableTotals` und Co. framework-frei bleiben. */
fun OrderLineEntity.toDomain(): OrderLine = OrderLine(
    id = id,
    tableNumber = tableNumber,
    articleId = articleId,
    nameSnapshot = nameSnapshot,
    unitPriceCents = unitPriceCents,
    qty = qty,
    createdAt = createdAt,
    deviceId = deviceId,
    voidedAt = voidedAt,
    settlementId = settlementId,
    updatedSeq = updatedSeq,
    note = note,
    pendingLocal = pendingLocal,
)

/**
 * Gibt `null` zurueck, wenn `kindRaw` unbekannt ist — genau wie Swifts
 * `PendingCommand.kind`. Ein Command, den diese App-Version nicht kennt, darf
 * die Queue nicht zum Absturz bringen.
 */
fun PendingCommandEntity.toSnapshot(): PendingCommandSnapshot? {
    val kind = CommandKind.fromRawValue(kindRaw) ?: return null
    return PendingCommandSnapshot(
        id = id,
        kind = kind,
        payload = payload,
        createdAt = createdAt,
        attemptCount = attemptCount,
        lastError = lastError,
        failedPermanently = failedPermanently,
    )
}

/**
 * Was gebucht werden soll: ein Artikel aus dem lokalen Katalog plus Menge.
 *
 * [note] gilt fuer alle Stueck dieser Position. Eine Buchung „2× mit Senf, 1×
 * ohne" gibt es bewusst nicht — wer das braucht, bucht zweimal.
 */
data class BookingItem(val article: ArticleEntity, val qty: Int, val note: String? = null)
