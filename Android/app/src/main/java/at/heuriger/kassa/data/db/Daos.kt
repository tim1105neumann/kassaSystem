package at.heuriger.kassa.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Lesen laeuft ueber `Flow` und damit ohne das Store-Lock: Room liefert pro
 * Emission einen in sich konsistenten Snapshot. Alles Schreibende ist `suspend`
 * und wird vom [at.heuriger.kassa.data.KassaStore] serialisiert.
 */
@Dao
interface ArticleDao {

    @Query("SELECT * FROM articles ORDER BY sort_order, name")
    fun observeAll(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles WHERE active = 1 ORDER BY sort_order, name")
    fun observeActive(): Flow<List<ArticleEntity>>

    @Query("SELECT * FROM articles ORDER BY sort_order, name")
    suspend fun all(): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun byId(id: String): ArticleEntity?

    @Upsert
    suspend fun upsertAll(articles: List<ArticleEntity>)

    @Query("DELETE FROM articles")
    suspend fun deleteAll()
}

@Dao
interface OrderLineDao {

    @Query("SELECT * FROM order_lines WHERE table_number = :tableNumber ORDER BY created_at")
    fun observeByTable(tableNumber: Int): Flow<List<OrderLineEntity>>

    @Query("SELECT * FROM order_lines ORDER BY created_at")
    fun observeAll(): Flow<List<OrderLineEntity>>

    @Query("SELECT * FROM order_lines WHERE table_number = :tableNumber ORDER BY created_at")
    suspend fun byTable(tableNumber: Int): List<OrderLineEntity>

    @Query("SELECT * FROM order_lines ORDER BY created_at")
    suspend fun all(): List<OrderLineEntity>

    @Query("SELECT * FROM order_lines WHERE id = :id")
    suspend fun byId(id: UUID): OrderLineEntity?

    @Upsert
    suspend fun upsert(line: OrderLineEntity)

    @Upsert
    suspend fun upsertAll(lines: List<OrderLineEntity>)

    /** Full Reload: nur der Serverspiegel faellt weg, optimistische Buchungen bleiben. */
    @Query("DELETE FROM order_lines WHERE pending_local = 0")
    suspend fun deleteMirrored()

    /** Rollback eines verworfenen Kassiervorgangs: die Zeilen sind wieder offen. */
    @Query("UPDATE order_lines SET settlement_id = NULL WHERE settlement_id = :settlementId")
    suspend fun clearSettlement(settlementId: UUID)

    @Query("DELETE FROM order_lines")
    suspend fun deleteAll()
}

@Dao
interface SettlementDao {

    @Query("SELECT * FROM settlements ORDER BY paid_at DESC")
    fun observeAll(): Flow<List<SettlementEntity>>

    @Query("SELECT * FROM settlements ORDER BY paid_at DESC")
    suspend fun all(): List<SettlementEntity>

    @Query("SELECT * FROM settlements WHERE id = :id")
    suspend fun byId(id: UUID): SettlementEntity?

    @Upsert
    suspend fun upsert(settlement: SettlementEntity)

    @Query("DELETE FROM settlements WHERE pending_local = 0")
    suspend fun deleteMirrored()

    /**
     * Nur der noch nicht bestaetigte Vorgang verschwindet. Was der Server schon
     * kennt, darf ein lokales Verwerfen nicht loeschen.
     */
    @Query("DELETE FROM settlements WHERE id = :id AND pending_local = 1")
    suspend fun deletePendingLocal(id: UUID)

    @Query("DELETE FROM settlements")
    suspend fun deleteAll()
}

@Dao
interface PendingCommandDao {

    /** Immer nach `order_index` — siehe Kommentar an [PendingCommandEntity]. */
    @Query("SELECT * FROM pending_commands ORDER BY order_index")
    fun observeAll(): Flow<List<PendingCommandEntity>>

    @Query("SELECT * FROM pending_commands ORDER BY order_index")
    suspend fun all(): List<PendingCommandEntity>

    /** FIFO, aber dauerhaft Fehlgeschlagenes wird uebersprungen. */
    @Query(
        "SELECT * FROM pending_commands WHERE failed_permanently = 0 " +
            "ORDER BY order_index LIMIT 1"
    )
    suspend fun nextOpen(): PendingCommandEntity?

    @Query("SELECT * FROM pending_commands WHERE id = :id")
    suspend fun byId(id: UUID): PendingCommandEntity?

    @Query("SELECT COUNT(*) FROM pending_commands WHERE failed_permanently = 0")
    fun observeOpenCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_commands WHERE failed_permanently = 1")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_commands WHERE failed_permanently = 0")
    suspend fun openCount(): Int

    @Query("SELECT COUNT(*) FROM pending_commands WHERE failed_permanently = 1")
    suspend fun failedCount(): Int

    /** `order_index` vergibt SQLite selbst (AUTOINCREMENT) — das ist die FIFO-Ordnung. */
    @Insert
    suspend fun insert(command: PendingCommandEntity): Long

    @Update
    suspend fun update(command: PendingCommandEntity)

    @Query("DELETE FROM pending_commands WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("DELETE FROM pending_commands")
    suspend fun deleteAll()
}
