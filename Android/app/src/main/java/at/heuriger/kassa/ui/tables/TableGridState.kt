package at.heuriger.kassa.ui.tables

import at.heuriger.kassa.data.KassaStore
import at.heuriger.kassa.data.SettingsStore
import at.heuriger.kassa.domain.TableTotals
import at.heuriger.kassa.wire.Money
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Ein Tisch, wie ihn das Raster kennt — ohne die Laufzeit, die sich jede halbe
 * Minute aendert. [openedAt] bleibt roh, damit ein Uhrentick nicht die ganze
 * Liste neu durch die Datenbank schickt.
 */
data class TableSummary(
    val number: Int,
    val isOpen: Boolean,
    val total: Money,
    val openedAt: Instant?,
)

/**
 * Rechnet die Bestellzeilen zu Tischen zusammen.
 *
 * Das ist die Stelle, die auf iOS `@Query` plus `Dictionary(grouping:)` ist. Hier
 * laeuft sie ueber [flowOn] auf einem Hintergrund-Dispatcher: an einem vollen
 * Abend sind das einige hundert Zeilen, die bei jeder Buchung neu gruppiert und
 * summiert werden — auf dem UI-Thread waere das genau das Ruckeln, das beim
 * Scrollen durch das Raster auffaellt.
 *
 * Gefiltert wird auf offene Zeilen, bevor gruppiert wird: Storniertes und
 * Kassiertes gehoert nicht mehr zum Tisch, und ein Tisch, dessen letzte Zeile
 * storniert wurde, muss wieder „frei" sein statt „offen, 0,00 €".
 */
fun tableSummaries(store: KassaStore, settings: SettingsStore): Flow<List<TableSummary>> =
    combine(
        store.observeAllLines(),
        settings.settings.map { it.tableCount }.distinctUntilChanged(),
    ) { lines, tableCount ->
        val openByTable = lines.filter { it.isOpen }.groupBy { it.tableNumber }

        // `maxOf(..., 1)`: eine Serverkonfiguration mit 0 Tischen wuerde sonst
        // ein leeres Raster liefern, aus dem niemand mehr herausfindet.
        (1..maxOf(tableCount, 1)).map { number ->
            val tableLines = openByTable[number].orEmpty()
            TableSummary(
                number = number,
                isOpen = tableLines.isNotEmpty(),
                total = TableTotals.openTotal(tableLines),
                openedAt = TableTotals.openedAt(tableLines),
            )
        }
    }.flowOn(Dispatchers.Default)

/**
 * Setzt die Laufzeit gegen [now] fest. Getrennt vom Flow, weil nur dieser
 * Schritt vom Uhrentick abhaengt — und 40 Subtraktionen sind auch auf dem
 * UI-Thread nichts.
 *
 * Negative Werte werden abgefangen: ein Geraet mit nachgehender Uhr bekaeme
 * sonst „seit -3 min" zu sehen.
 */
fun TableSummary.toTileState(now: Instant): TableTileState = TableTileState(
    number = number,
    isOpen = isOpen,
    total = total,
    openMinutes = openedAt?.let {
        Duration.between(it, now).toMinutes().coerceAtLeast(0L).toInt()
    } ?: 0,
)
