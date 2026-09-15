package at.heuriger.kassa.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Der lokale Spiegel plus Offline-Queue.
 *
 * Kein `fallbackToDestructiveMigration`: in dieser Datenbank liegen Buchungen,
 * die der Server noch nie gesehen hat. Ein Schema-Sprung ohne Migration wuerde
 * genau die wegwerfen.
 */
@Database(
    entities = [
        ArticleEntity::class,
        OrderLineEntity::class,
        SettlementEntity::class,
        PendingCommandEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KassaDatabase : RoomDatabase() {

    abstract fun articleDao(): ArticleDao

    abstract fun orderLineDao(): OrderLineDao

    abstract fun settlementDao(): SettlementDao

    abstract fun pendingCommandDao(): PendingCommandDao

    companion object {
        const val NAME = "kassa.db"

        /**
         * Notiz pro Bestellzeile.
         *
         * `ADD COLUMN note TEXT` ohne `DEFAULT`: das generierte Schema erwartet
         * eine Spalte ohne Default-Klausel, genau wie bei `voided_at` und
         * `settlement_id`. Ein `DEFAULT NULL` waere ein Unterschied, den Room
         * erst beim Oeffnen auf einem echten Altgeraet als Identitaetsbruch
         * melden wuerde.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE order_lines ADD COLUMN note TEXT")
            }
        }

        fun open(context: Context): KassaDatabase =
            Room.databaseBuilder(context.applicationContext, KassaDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
