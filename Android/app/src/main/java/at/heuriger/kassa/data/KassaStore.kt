package at.heuriger.kassa.data

import androidx.room.withTransaction
import at.heuriger.kassa.data.db.ArticleEntity
import at.heuriger.kassa.data.db.KassaDatabase
import at.heuriger.kassa.data.db.OrderLineEntity
import at.heuriger.kassa.data.db.PendingCommandEntity
import at.heuriger.kassa.data.db.SettlementEntity
import at.heuriger.kassa.domain.OrderLine
import at.heuriger.kassa.wire.ArticleDto
import at.heuriger.kassa.wire.BusinessDay
import at.heuriger.kassa.wire.CreateOrderLinesRequest
import at.heuriger.kassa.wire.CreateSettlementRequest
import at.heuriger.kassa.wire.KassaClock
import at.heuriger.kassa.wire.KassaJson
import at.heuriger.kassa.wire.Money
import at.heuriger.kassa.wire.NewOrderLine
import at.heuriger.kassa.wire.OrderLineDto
import at.heuriger.kassa.wire.SettlementDto
import at.heuriger.kassa.wire.SettlementLineSelection
import at.heuriger.kassa.wire.SyncResponse
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lokaler Spiegel und Offline-Queue. Spiegel von `LocalStore` aus
 * `App/Sources/Sync/LocalStore.swift`.
 *
 * **Nebenlaeufigkeit.** Auf iOS ist `LocalStore` an den MainActor gebunden, das
 * serialisiert dort jede Operation. Hier uebernimmt das ein [Mutex] um jede
 * schreibende Operation, innen eine Room-Transaktion.
 *
 * Bewusst **kein** `Dispatchers.IO.limitedParallelism(1)`: `withTransaction`
 * wechselt intern auf Rooms eigenen Transaktions-Dispatcher, eine
 * Thread-Konfinierung von aussen waere dort still aufgehoben. Der Mutex ist ein
 * Lock ueber die logische Operation und ueberlebt den Dispatcher-Wechsel.
 *
 * Der Mutex ist nicht reentrant. Nur oeffentliche Methoden nehmen ihn; private
 * Helfer heissen `...Locked` und setzen voraus, dass er gehalten wird.
 *
 * Lesen laeuft ueber `Flow`-liefernde DAOs und ohne Lock — Room liefert pro
 * Emission einen konsistenten Snapshot.
 */
class KassaStore(
    private val db: KassaDatabase,
    private val settings: SettingsStore,
    /**
     * App-weiter Scope. Die UI-Aufrufe kehren sofort zurueck und schreiben hier
     * weiter; ein Wegnavigieren mitten in der Buchung darf sie nicht abbrechen.
     */
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val json = KassaJson.instance

    private val articleDao = db.articleDao()
    private val lineDao = db.orderLineDao()
    private val settlementDao = db.settlementDao()
    private val commandDao = db.pendingCommandDao()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    // MARK: - Lesen (ohne Lock)

    fun observeLines(tableNumber: Int): Flow<List<OrderLine>> =
        lineDao.observeByTable(tableNumber).map { entities -> entities.map { it.toDomain() } }

    fun observeAllLines(): Flow<List<OrderLine>> =
        lineDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    fun observeArticles() = articleDao.observeAll()

    fun observeActiveArticles() = articleDao.observeActive()

    fun observeSettlements() = settlementDao.observeAll()

    fun observePendingCommands(): Flow<List<PendingCommandSnapshot>> =
        commandDao.observeAll().map { commands -> commands.mapNotNull { it.toSnapshot() } }

    fun observeOpenPendingCount(): Flow<Int> = commandDao.observeOpenCount()

    fun observeFailedPendingCount(): Flow<Int> = commandDao.observeFailedCount()

    suspend fun lines(tableNumber: Int): List<OrderLine> =
        lineDao.byTable(tableNumber).map { it.toDomain() }

    suspend fun allLines(): List<OrderLine> = lineDao.all().map { it.toDomain() }

    suspend fun articles() = articleDao.all()

    suspend fun article(id: String) = articleDao.byId(id)

    suspend fun settlements() = settlementDao.all()

    suspend fun pendingCommands(): List<PendingCommandSnapshot> =
        commandDao.all().mapNotNull { it.toSnapshot() }

    suspend fun openPendingCount(): Int = commandDao.openCount()

    suspend fun failedPendingCount(): Int = commandDao.failedCount()

    /**
     * FIFO nach `order_index`, dauerhaft Fehlgeschlagenes wird uebersprungen —
     * sonst steht die ganze Queue hinter einem einzigen 4xx still.
     *
     * Nimmt bewusst kein Lock: ein einzelner Lesezugriff braucht keinen, und die
     * SyncEngine ruft ihn in ihrer Schleife oft auf.
     */
    suspend fun nextPendingCommand(): PendingCommandSnapshot? =
        commandDao.nextOpen()?.toSnapshot()

    // MARK: - Zustand

    fun setConnection(state: ConnectionState) = _status.update { it.copy(connection = state) }

    fun setLastError(message: String?) = _status.update { it.copy(lastErrorMessage = message) }

    fun setConflict(message: String?) = _status.update { it.copy(conflictMessage = message) }

    // MARK: - Optimistisches Schreiben (kehrt sofort zurueck)

    /**
     * Bucht Artikel auf einen Tisch: sofort lokal sichtbar, gleichzeitig in die
     * Queue. Die UI wartet nie auf das Netz.
     *
     * Der zurueckgegebene [Job] ist fuer Tests und fuer ein „gerade gespeichert"
     * in der UI da; die Buchung laeuft auch ohne ihn zu Ende.
     */
    fun addLines(tableNumber: Int, items: List<BookingItem>): Job = write {
        val effective = items.filter { it.qty > 0 }
        if (effective.isEmpty()) return@write

        val now = KassaClock.now()
        val deviceId = settings.deviceId
        val newLines = effective.map { item ->
            val id = UUID.randomUUID()
            lineDao.upsert(
                OrderLineEntity(
                    id = id,
                    tableNumber = tableNumber,
                    articleId = item.article.id,
                    nameSnapshot = item.article.name,
                    unitPriceCents = item.article.priceCents,
                    qty = item.qty,
                    createdAt = now,
                    deviceId = deviceId,
                    note = item.note,
                )
            )
            NewOrderLine(
                id = id,
                tableNumber = tableNumber,
                articleId = item.article.id,
                qty = item.qty,
                createdAt = now,
                note = item.note,
            )
        }
        enqueueLocked(
            CommandKind.ADD_LINES,
            json.encodeToString(CreateOrderLinesRequest.serializer(), CreateOrderLinesRequest(newLines)),
        )
    }

    /**
     * Storniert eine Zeile.
     *
     * Nimmt die ID statt des Objekts — anders als iOS, wo `LocalOrderLine` eine
     * lebende Referenz ist. Die Compose-UI haelt nur unveraenderliche Snapshots;
     * die Zeile wird deshalb in der Transaktion frisch gelesen.
     */
    fun voidLine(lineId: UUID): Job = write {
        val line = lineDao.byId(lineId) ?: return@write
        if (!line.isOpen) return@write
        lineDao.upsert(line.copy(voidedAt = KassaClock.now()))
        enqueueLocked(
            CommandKind.VOID_LINE,
            json.encodeToString(VoidLineCommand.serializer(), VoidLineCommand(lineId)),
        )
    }

    /**
     * Menge aendern. Der Vertrag kennt nur „ganze Zeile stornieren", also wird
     * beim Verringern die alte Zeile storniert und eine neue mit der Restmenge
     * gebucht. Erhoehen ist einfach eine zusaetzliche Buchung.
     */
    fun changeQty(lineId: UUID, newQty: Int): Job = write {
        val line = lineDao.byId(lineId) ?: return@write
        if (!line.isOpen || newQty == line.qty) return@write

        if (newQty > line.qty) {
            bookLocked(line, qty = newQty - line.qty)
            return@write
        }

        lineDao.upsert(line.copy(voidedAt = KassaClock.now()))
        enqueueLocked(
            CommandKind.VOID_LINE,
            json.encodeToString(VoidLineCommand.serializer(), VoidLineCommand(line.id)),
        )

        val remaining = maxOf(newQty, 0)
        if (remaining > 0) bookLocked(line, qty = remaining)
    }

    /**
     * Kassieren. Voll kassierte Zeilen bekommen lokal die Settlement-ID,
     * teilweise kassierte werden lokal um die Menge reduziert — der Server
     * splittet die Zeile und liefert die Wahrheit beim naechsten Delta nach.
     *
     * Die ID steht sofort fest, damit die UI sie nicht abwarten muss.
     */
    fun settle(
        tableNumber: Int,
        selections: List<SettlementLineSelection>,
        total: Money,
        tip: Money = Money.ZERO,
        paidAt: Instant = KassaClock.now(),
    ): SettleHandle {
        val settlementId = UUID.randomUUID()
        val job = write {
            val byId = lineDao.byTable(tableNumber).associateBy { it.id }

            for (selection in selections) {
                val line = byId[selection.lineId] ?: continue
                if (!line.isOpen) continue
                if (selection.qty >= line.qty) {
                    lineDao.upsert(line.copy(settlementId = settlementId))
                } else {
                    lineDao.upsert(line.copy(qty = line.qty - selection.qty))
                }
            }

            settlementDao.upsert(
                SettlementEntity(
                    id = settlementId,
                    tableNumber = tableNumber,
                    totalCents = total.cents,
                    paidAt = paidAt,
                    deviceId = settings.deviceId,
                    businessDay = BusinessDay.day(paidAt, settings.cutoffHour),
                    tipCents = tip.cents,
                )
            )

            enqueueLocked(
                CommandKind.SETTLE,
                json.encodeToString(
                    CreateSettlementRequest.serializer(),
                    CreateSettlementRequest(
                        id = settlementId,
                        tableNumber = tableNumber,
                        lines = selections,
                        amountCents = total.cents,
                        paidAt = paidAt,
                        tipCents = tip.cents,
                    ),
                ),
            )
        }
        return SettleHandle(settlementId, job)
    }

    /** Ergebnis von [settle]: die ID gilt sofort, das Schreiben laeuft noch. */
    data class SettleHandle(val settlementId: UUID, val job: Job)

    // MARK: - Queue

    /** Direktes Einreihen — fuer Tests und fuer Commands ohne lokalen Seiteneffekt. */
    suspend fun enqueue(kind: CommandKind, payload: String, createdAt: Instant = KassaClock.now()) {
        mutex.withLock { db.withTransaction { enqueueLocked(kind, payload, createdAt) } }
    }

    suspend fun completeCommand(id: UUID) {
        mutex.withLock { db.withTransaction { commandDao.deleteById(id) } }
    }

    suspend fun recordFailure(id: UUID, message: String, permanent: Boolean) {
        mutex.withLock {
            db.withTransaction {
                val command = commandDao.byId(id) ?: return@withTransaction
                commandDao.update(
                    command.copy(
                        attemptCount = command.attemptCount + 1,
                        lastError = message,
                        failedPermanently = permanent,
                    )
                )
            }
        }
    }

    /**
     * Ein verworfener Kassiervorgang darf lokal nicht als kassiert stehen
     * bleiben — der Server weiss nichts davon.
     */
    suspend fun discardCommand(id: UUID) {
        mutex.withLock {
            db.withTransaction {
                val command = commandDao.byId(id) ?: return@withTransaction
                if (CommandKind.fromRawValue(command.kindRaw) == CommandKind.SETTLE) {
                    val request = runCatching {
                        json.decodeFromString(CreateSettlementRequest.serializer(), command.payload)
                    }.getOrNull()
                    if (request != null) rollbackSettlementLocked(request.id)
                }
                commandDao.deleteById(id)
            }
        }
    }

    suspend fun rollbackSettlement(settlementId: UUID) {
        mutex.withLock { db.withTransaction { rollbackSettlementLocked(settlementId) } }
    }

    // MARK: - Merge

    suspend fun merge(response: SyncResponse) {
        mutex.withLock {
            // `settings` liegt in DataStore, nicht in der Room-Transaktion. Der
            // Wert wird deshalb in der Transaktion nur berechnet und erst danach
            // geschrieben — beides unter demselben Lock, also trotzdem atomar
            // gegenueber anderen Store-Operationen.
            var maxSeq = settings.maxSeq

            db.withTransaction {
                if (response.isFullReload) {
                    replaceMirrorLocked()
                    maxSeq = 0
                }
                mergeLinesLocked(response.lines)
                mergeSettlementsLocked(response.settlements)
                maxSeq = maxOf(maxSeq, response.maxSeq)
            }

            settings.setMaxSeq(maxSeq)
            _status.update { it.copy(lastSyncAt = KassaClock.now()) }
        }
    }

    /**
     * `active = false` statt loeschen: alte Bestellzeilen referenzieren den
     * Artikel weiter, und ein CSV-Import ohne den Artikel heisst „nicht mehr auf
     * der Karte", nicht „hat es nie gegeben".
     */
    suspend fun replaceArticles(dtos: List<ArticleDto>) {
        mutex.withLock {
            db.withTransaction {
                val existing = articleDao.all().associateBy { it.id }
                val seen = dtos.mapTo(mutableSetOf()) { it.id }

                val updated = dtos.map { dto ->
                    existing[dto.id]?.applying(dto) ?: ArticleEntity.from(dto)
                }
                val retired = existing.values
                    .filter { it.id !in seen && it.active }
                    .map { it.copy(active = false) }

                articleDao.upsertAll(updated + retired)
            }
        }
    }

    // MARK: - Abmelden

    suspend fun wipeAll() {
        mutex.withLock {
            db.withTransaction {
                lineDao.deleteAll()
                settlementDao.deleteAll()
                commandDao.deleteAll()
                articleDao.deleteAll()
            }
        }
    }

    // MARK: - Helfer (Lock wird vorausgesetzt)

    /**
     * Neu buchen auf Basis einer bestehenden Zeile. Die Notiz reist mit: ohne
     * das verliert schon ein Tipp auf Minus den Sonderwunsch still — und genau
     * dieser Pfad erzeugt den Stornobon in der Kueche.
     */
    private suspend fun bookLocked(source: OrderLineEntity, qty: Int) {
        val now = KassaClock.now()
        val id = UUID.randomUUID()
        lineDao.upsert(
            OrderLineEntity(
                id = id,
                tableNumber = source.tableNumber,
                articleId = source.articleId,
                nameSnapshot = source.nameSnapshot,
                unitPriceCents = source.unitPriceCents,
                qty = qty,
                createdAt = now,
                deviceId = settings.deviceId,
                note = source.note,
            )
        )
        enqueueLocked(
            CommandKind.ADD_LINES,
            json.encodeToString(
                CreateOrderLinesRequest.serializer(),
                CreateOrderLinesRequest(
                    listOf(
                        NewOrderLine(
                            id = id,
                            tableNumber = source.tableNumber,
                            articleId = source.articleId,
                            qty = qty,
                            createdAt = now,
                            note = source.note,
                        )
                    )
                ),
            ),
        )
    }

    private suspend fun enqueueLocked(
        kind: CommandKind,
        payload: String,
        createdAt: Instant = KassaClock.now(),
    ) {
        commandDao.insert(
            PendingCommandEntity(
                id = UUID.randomUUID(),
                kindRaw = kind.rawValue,
                payload = payload,
                createdAt = createdAt,
            )
        )
    }

    private suspend fun rollbackSettlementLocked(settlementId: UUID) {
        lineDao.clearSettlement(settlementId)
        settlementDao.deletePendingLocal(settlementId)
    }

    /**
     * Spiegel ersetzen heisst: alles wegwerfen, was vom Server kam. Noch nicht
     * bestaetigte lokale Buchungen und die komplette Warteschlange bleiben —
     * sonst verschwinden Bestellungen, die noch gar nicht abgesetzt wurden.
     */
    private suspend fun replaceMirrorLocked() {
        lineDao.deleteMirrored()
        settlementDao.deleteMirrored()
    }

    private suspend fun mergeLinesLocked(dtos: List<OrderLineDto>) {
        if (dtos.isEmpty()) return
        val existing = lineDao.all().associateBy { it.id }
        for (dto in dtos) {
            val local = existing[dto.id]
            val entity = when {
                local == null -> OrderLineEntity.from(dto)
                // Ein aelterer Serverstand darf einen neueren nicht zurueckdrehen.
                dto.updatedSeq >= local.updatedSeq || local.pendingLocal -> local.applying(dto)
                else -> continue
            }
            lineDao.upsert(entity)
        }
    }

    private suspend fun mergeSettlementsLocked(dtos: List<SettlementDto>) {
        if (dtos.isEmpty()) return
        val existing = settlementDao.all().associateBy { it.id }
        for (dto in dtos) {
            val local = existing[dto.id]
            val entity = when {
                local == null -> SettlementEntity.from(dto)
                dto.updatedSeq >= local.updatedSeq || local.pendingLocal -> local.applying(dto)
                else -> continue
            }
            settlementDao.upsert(entity)
        }
    }

    /**
     * Schreibender Aufruf aus der UI: laeuft im app-weiten Scope weiter, auch
     * wenn der Bildschirm verlassen wird.
     *
     * Fehler landen in [SyncStatus.lastErrorMessage] statt als Absturz — das ist
     * das Pendant zu Swifts `save()`-Fehlerzweig. `CancellationException` wird
     * durchgereicht, sonst waere der Scope nicht mehr sauber abbrechbar.
     */
    private fun write(block: suspend () -> Unit): Job = scope.launch {
        try {
            mutex.withLock { db.withTransaction { block() } }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            _status.update {
                it.copy(lastErrorMessage = "Lokal speichern fehlgeschlagen: ${error.message}")
            }
        }
    }
}
