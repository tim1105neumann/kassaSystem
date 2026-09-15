package at.heuriger.kassa.ui.tables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.heuriger.kassa.R
import at.heuriger.kassa.data.ConnectionState
import at.heuriger.kassa.ui.components.ConnectionBanner
import at.heuriger.kassa.ui.theme.KassaTheme
import at.heuriger.kassa.wire.Money
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource

/**
 * Was eine Kachel zum Zeichnen braucht — fertig gerechnet.
 *
 * Bewusst nicht die Bestellzeilen selbst: das Gruppieren und Summieren von
 * mehreren hundert Zeilen gehoert nicht in die Komposition, sondern in den Flow
 * davor (siehe `TableGridState`). Die Kachel bekommt nur noch Zahlen.
 *
 * [openMinutes] statt eines Zeitstempels, damit die Kachel nicht selbst rechnen
 * muss und der 30-Sekunden-Takt an genau einer Stelle liegt.
 */
data class TableTileState(
    val number: Int,
    val isOpen: Boolean,
    val total: Money,
    val openMinutes: Int,
)

/**
 * Das Tischraster. Spiegel von `TableGridView.swift`.
 *
 * Zustandslos — alle Werte kommen herein, alle Aktionen gehen heraus. Dadurch
 * laesst sich das Raster in der Vorschau mit erfundenen Tischen zeigen, ohne
 * Datenbank und ohne Server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableGridScreen(
    tiles: List<TableTileState>,
    connection: ConnectionState,
    openPendingCount: Int,
    failedPendingCount: Int,
    conflictMessage: String?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDismissConflict: () -> Unit,
    onTableClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.groupedBackground),
    ) {
        ConnectionBanner(
            connection = connection,
            openCount = openPendingCount,
            failedCount = failedPendingCount,
            modifier = Modifier
                .padding(horizontal = dimens.screenPadding)
                .padding(bottom = 8.dp),
        )

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyVerticalGrid(
                // Adaptiv statt fester Spaltenzahl: dasselbe Raster fuellt das
                // Telefon hochkant mit drei Spalten und ein Tablet mit acht,
                // ohne Sonderfall im Code.
                columns = GridCells.Adaptive(dimens.tileMinWidth),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = dimens.screenPadding,
                    end = dimens.screenPadding,
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
                verticalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
            ) {
                items(tiles, key = { it.number }) { tile ->
                    TableTile(tile = tile, onClick = { onTableClick(tile.number) })
                }
            }
        }
    }

    if (conflictMessage != null) {
        AlertDialog(
            onDismissRequest = onDismissConflict,
            title = { Text(stringResource(R.string.dialog_notice_title)) },
            text = { Text(conflictMessage) },
            confirmButton = {
                TextButton(onClick = onDismissConflict) {
                    Text(stringResource(R.string.dialog_ok))
                }
            },
        )
    }
}

/**
 * Eine Kachel. Offen heisst Akzentfarbe und weisse Schrift, frei heisst helle
 * Flaeche mit grauer Umrandung — auf einen Blick unterscheidbar, auch ohne die
 * Zahl zu lesen.
 */
@Composable
private fun TableTile(
    tile: TableTileState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens

    val openSince = openSinceText(tile.openMinutes)
    val spoken = if (tile.isOpen) {
        stringResource(R.string.table_accessibility_open, tile.number, tile.total.formatted, openSince)
    } else {
        stringResource(R.string.table_accessibility_free, tile.number)
    }

    val shape = RoundedCornerShape(dimens.cornerRadius)
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Mindest-, nicht Festhoehe: bei grosser Systemschrift waechst die
            // Kachel mit, statt den Betrag abzuschneiden.
            .defaultMinSize(minHeight = dimens.tileMinHeight)
            .background(if (tile.isOpen) colors.accent else colors.surface, shape)
            .then(
                if (tile.isOpen) Modifier
                else Modifier.border(dimens.hairline, colors.separator, shape)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(dimens.tilePadding)
            // Die Kachel ist eine Aussage und ein Knopf, nicht drei Textstuecke.
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Das Pendant zu `minimumScaleFactor(0.6)`: dreistellige Tischnummern
        // schrumpfen, statt die Kachel zu sprengen.
        BasicText(
            text = "${tile.number}",
            style = KassaTheme.typography.tileNumber.copy(
                color = if (tile.isOpen) colors.onAccent else colors.label,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 20.sp,
                maxFontSize = 34.sp,
                stepSize = 1.sp,
            ),
        )

        if (tile.isOpen) {
            BasicText(
                text = tile.total.formatted,
                style = KassaTheme.typography.amount.copy(color = colors.onAccent),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 11.sp,
                    maxFontSize = 17.sp,
                    stepSize = 1.sp,
                ),
            )
            Text(
                text = openSince,
                style = KassaTheme.typography.caption,
                // Deckend, nicht abgeschwaecht: 85 % Weiss auf der Akzentfarbe
                // liegen bei rund 3,8:1, und fuer 12sp verlangt AA 4,5:1.
                color = colors.onAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = stringResource(R.string.table_free),
                style = KassaTheme.typography.subheadline,
                color = colors.secondaryLabel,
            )
        }
    }
}

/** „seit 42 min" bzw. „seit 2 h 05 min" — wie `openSince` in der Vorlage. */
@Composable
private fun openSinceText(minutes: Int): String =
    if (minutes < 60) {
        stringResource(R.string.table_open_since_minutes, minutes)
    } else {
        stringResource(R.string.table_open_since_hours, minutes / 60, minutes % 60)
    }

/**
 * Der 30-Sekunden-Takt aus der Vorlage (`Timer.publish(every: 30)`).
 *
 * Liegt hier als eigener Baustein, damit genau *eine* Stelle die Uhr
 * weiterstellt. Jede Kachel mit eigenem Timer waere bei 40 Tischen 40-mal
 * Aufwachen pro halbe Minute.
 */
@Composable
fun rememberMinuteTicker(): Long {
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick += 1
        }
    }
    return tick
}

// MARK: - Vorschau

private fun previewTiles(): List<TableTileState> = List(12) { index ->
    val number = index + 1
    when (number % 4) {
        0 -> TableTileState(number, isOpen = true, total = Money(12_340), openMinutes = 135)
        1 -> TableTileState(number, isOpen = false, total = Money.ZERO, openMinutes = 0)
        2 -> TableTileState(number, isOpen = true, total = Money(620), openMinutes = 7)
        else -> TableTileState(number, isOpen = false, total = Money.ZERO, openMinutes = 0)
    }
}

@Preview(name = "Raster hell", showBackground = true)
@Composable
private fun TableGridPreview() {
    KassaTheme(darkTheme = false) { TableGridPreviewBody(null) }
}

@Preview(name = "Raster dunkel", showBackground = true)
@Composable
private fun TableGridDarkPreview() {
    KassaTheme(darkTheme = true) { TableGridPreviewBody(null) }
}

@Preview(name = "Raster grosse Schrift", showBackground = true, fontScale = 1.5f)
@Composable
private fun TableGridLargeFontPreview() {
    KassaTheme(darkTheme = false) { TableGridPreviewBody(null) }
}

@Preview(name = "Raster mit Hinweis", showBackground = true)
@Composable
private fun TableGridConflictPreview() {
    KassaTheme(darkTheme = false) {
        TableGridPreviewBody("Tisch 4 wurde bereits von einem anderen Gerät kassiert.")
    }
}

@Composable
private fun TableGridPreviewBody(conflict: String?) {
    TableGridScreen(
        tiles = previewTiles(),
        connection = ConnectionState.CONNECTED,
        openPendingCount = 0,
        failedPendingCount = 0,
        conflictMessage = conflict,
        isRefreshing = false,
        onRefresh = {},
        onDismissConflict = {},
        onTableClick = {},
    )
}
