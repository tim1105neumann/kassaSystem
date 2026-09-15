package at.heuriger.kassa.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Die Migration von Version 1 auf 2.
 *
 * Dieser Test ist der Grund, warum `exportSchema` an ist: in der Datenbank auf
 * dem Geraet liegen Buchungen, die der Server noch nie gesehen hat, und es gibt
 * bewusst kein `fallbackToDestructiveMigration`. Ein Schemafehler faellt hier
 * auf oder erst am Abend im Heurigen.
 *
 * Laeuft ueber `FrameworkSQLiteOpenHelperFactory`, also ueber genau denselben
 * Pfad wie [KassaDatabase.open] — nicht ueber einen Treiber, der
 * `migrate(SQLiteConnection)` aufrufen wuerde.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KassaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `Migration 1 nach 2 haengt die Notizspalte an und behaelt die Buchung`() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO order_lines
                    (id, table_number, article_id, name_snapshot, unit_price_cents, qty,
                     created_at, device_id, voided_at, settlement_id, updated_seq, pending_local)
                VALUES ('$LINE_ID', 7, 'a1', 'Kaesekrainer', 620, 2,
                        1700000000, 'geraet-a', NULL, NULL, 0, 1)
                """.trimIndent()
            )
        }

        // Validiert gegen `schemas/.../2.json`: eine abweichende Spaltenform oder
        // ein anderer Default waere hier ein Fehler, nicht erst beim Oeffnen.
        val migrated = helper.runMigrationsAndValidate(NAME, 2, true, KassaDatabase.MIGRATION_1_2)

        migrated.query("SELECT qty, pending_local, note FROM order_lines WHERE id = '$LINE_ID'")
            .use { cursor ->
                assertTrue("Die Buchung aus Version 1 ist verschwunden", cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
                // Noch nicht abgesetzt: genau die Zeilen, die eine zerstoerende
                // Migration vernichtet haette.
                assertEquals(1, cursor.getInt(1))
                assertTrue("Eine Zeile aus Version 1 hat keine Notiz", cursor.isNull(2))
            }
    }

    @Test
    fun `nach der Migration laesst sich eine Notiz schreiben und lesen`() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO order_lines
                    (id, table_number, article_id, name_snapshot, unit_price_cents, qty,
                     created_at, device_id, voided_at, settlement_id, updated_seq, pending_local)
                VALUES ('$LINE_ID', 7, 'a1', 'Kaesekrainer', 620, 1,
                        1700000000, 'geraet-a', NULL, NULL, 0, 0)
                """.trimIndent()
            )
        }

        val migrated = helper.runMigrationsAndValidate(NAME, 2, true, KassaDatabase.MIGRATION_1_2)
        migrated.execSQL("UPDATE order_lines SET note = 'ohne Senf' WHERE id = '$LINE_ID'")

        migrated.query("SELECT note FROM order_lines WHERE id = '$LINE_ID'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ohne Senf", cursor.getString(0))
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
        const val LINE_ID = "11111111-1111-1111-1111-111111111111"
    }
}
