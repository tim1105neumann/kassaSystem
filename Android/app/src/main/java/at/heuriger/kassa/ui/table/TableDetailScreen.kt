package at.heuriger.kassa.ui.table

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.heuriger.kassa.R
import at.heuriger.kassa.domain.OrderLine
import at.heuriger.kassa.domain.TableTotals
import at.heuriger.kassa.ui.components.KassaAmount
import at.heuriger.kassa.ui.components.KassaBarButton
import at.heuriger.kassa.ui.components.KassaBottomBar
import at.heuriger.kassa.ui.components.KassaEmptyState
import at.heuriger.kassa.ui.components.KassaFilledButton
import at.heuriger.kassa.ui.components.KassaRowDivider
import at.heuriger.kassa.ui.components.KassaStepperButton
import at.heuriger.kassa.ui.components.KassaTintedButton
import at.heuriger.kassa.ui.theme.KassaTheme
import at.heuriger.kassa.wire.Money
import java.time.Instant
import java.util.UUID

/**
 * Der offene Tisch. Spiegel von `TableDetailView.swift`.
 *
 * Zustandslos wie das Raster: alle Zeilen kommen herein, alle Aenderungen gehen
 * als Aufrufe heraus. Dadurch laesst sich der Bildschirm in der Vorschau mit
 * erfundenen Zeilen zeigen, ohne Datenbank und ohne Server.
 *
 * **Abweichung von der Vorlage, und zwar eine notwendige.** iOS reicht das
 * lebende `LocalOrderLine`-Objekt an `store.changeQty(of:to:)`. Hier geht nur
 * die [UUID] hinaus: die Compose-Oberflaeche haelt unveraenderliche Momentaufnahmen,
 * und eine Momentaufnahme, die zwischen Anzeige und Tastendruck veraltet ist,
 * wuerde sonst eine falsche Menge festschreiben. Der Store liest die Zeile in
 * seiner Transaktion frisch nach.
 */
@Composable
fun TableDetailScreen(
    tableNumber: Int,
    lines: List<OrderLine>,
    onBack: () -> Unit,
    onChangeQty: (lineId: UUID, newQty: Int) -> Unit,
    onAddArticles: () -> Unit,
    onSettle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens

    // Sortiert und gefiltert an genau einer Stelle — die Vorlage macht das im
    // `@Query`, hier ist es die Domaenenfunktion, die auch das Raster benutzt.
    val openLines = TableTotals.openLines(lines)
    val total = TableTotals.openTotal(openLines)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.groupedBackground),
    ) {
        at.heuriger.kassa.ui.components.KassaNavBar(
            title = stringResource(R.string.table_title, tableNumber),
            leading = {
                KassaBarButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                )
            },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (openLines.isEmpty()) {
                KassaEmptyState(
                    icon = Icons.Outlined.Inbox,
                    title = stringResource(R.string.detail_empty_title),
                    message = stringResource(R.string.detail_empty_message),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = dimens.screenPadding),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    itemsIndexed(openLines, key = { _, line -> line.id }) { index, line ->
                        Column(
                            modifier = Modifier.background(
                                colors.surface,
                                cardShape(index, openLines.size),
                            )
                        ) {
                            if (index > 0) KassaRowDivider()
                            OrderLineRow(
                                line = line,
                                onChangeQty = { onChangeQty(line.id, it) },
                            )
                        }
                    }
                }
            }
        }

        KassaBottomBar {
            // Ausserhalb des `semantics`-Lambdas gebildet: `stringResource`
            // braucht eine Komposition, das Lambda laeuft in keiner.
            val totalSpoken = stringResource(R.string.detail_a11y_total, total.formatted)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Summe und Beschriftung sind eine Aussage, kein Paar
                    // Textstuecke: TalkBack soll „Gesamtsumme 24,80 €" am
                    // Stueck vorlesen.
                    .clearAndSetSemantics { contentDescription = totalSpoken },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.detail_total),
                    style = KassaTheme.typography.headline.copy(fontSize = 22.sp),
                    color = colors.label,
                )
                Box(modifier = Modifier.weight(1f))
                KassaAmount(text = total.formatted, maxFontSize = 40.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Ohne Symbol: die Beschriftung traegt ihr Pluszeichen schon
                // selbst, ein zweites daneben liest sich als „+ + Artikel".
                KassaTintedButton(
                    text = stringResource(R.string.detail_add_articles),
                    onClick = onAddArticles,
                    modifier = Modifier.weight(1f),
                )
                KassaFilledButton(
                    text = stringResource(R.string.detail_settle),
                    onClick = onSettle,
                    enabled = openLines.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    minHeight = 60.dp,
                )
            }
        }
    }
}

/**
 * Eine Bestellzeile: Name und Zeilensumme oben, darunter Stueckpreis und
 * Mengensteuerung.
 *
 * Minus auf 0 storniert die Zeile — das ist die Vorlage und zugleich der einzige
 * Weg, den der Vertrag kennt: „ganze Zeile stornieren", nicht „Menge senken".
 * Deshalb ist der Minusknopf bei Menge 1 auch nicht gesperrt, fragt aber
 * vorher nach: ein Fehltipp am Tisch soll keine gebuchte Position sofort
 * loeschen.
 */
@Composable
private fun OrderLineRow(
    line: OrderLine,
    onChangeQty: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KassaTheme.colors

    // Reiner UI-Zustand fuer die Rueckfrage bei Menge 1 — der Store bekommt
    // erst nach der Bestaetigung den gewohnten Aufruf zu sehen. Lokal an
    // dieser Zeile gehalten (der LazyColumn-Key ist line.id), deshalb ist die
    // betroffene Zeile immer eindeutig, auch wenn sich die Liste darunter
    // aendert.
    var showCancelConfirmation by remember { mutableStateOf(false) }

    val note = line.note?.takeIf { it.isNotBlank() }
    val lineSpoken = if (note != null) {
        stringResource(
            R.string.detail_a11y_line_note,
            line.nameSnapshot,
            line.lineTotal.formatted,
            note,
        )
    } else {
        stringResource(
            R.string.detail_a11y_line,
            line.nameSnapshot,
            line.lineTotal.formatted,
        )
    }
    val qtySpoken = stringResource(R.string.detail_a11y_qty, line.qty)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = lineSpoken },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = line.nameSnapshot,
                style = KassaTheme.typography.headline,
                color = colors.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = line.lineTotal.formatted,
                style = KassaTheme.typography.amount,
                color = colors.label,
                maxLines = 1,
            )
        }

        if (note != null) {
            // Nur Anzeige: gebuchte Zeilen nachtraeglich zu aendern braeuchte
            // einen eigenen Command im Protokoll, den es nicht gibt. Die Notiz
            // steckt schon in der Zeilenbeschreibung, deshalb ohne eigene
            // Semantik.
            Text(
                text = note,
                style = KassaTheme.typography.subheadline,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.detail_unit_price,
                    Money(line.unitPriceCents).formatted,
                ),
                style = KassaTheme.typography.subheadline,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            KassaStepperButton(
                icon = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.detail_a11y_decrement),
                onClick = {
                    if (line.qty == 1) {
                        showCancelConfirmation = true
                    } else {
                        onChangeQty(line.qty - 1)
                    }
                },
            )
            Text(
                text = "${line.qty}",
                style = KassaTheme.typography.amount.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = colors.label,
                modifier = Modifier
                    .widthIn(min = 44.dp)
                    .semantics { contentDescription = qtySpoken },
                maxLines = 1,
            )
            KassaStepperButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.detail_a11y_increment),
                onClick = { onChangeQty(line.qty + 1) },
            )
        }
    }

    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmation = false },
            title = { Text(stringResource(R.string.detail_cancel_line_title)) },
            text = {
                Text(
                    stringResource(R.string.detail_cancel_line_message, line.nameSnapshot),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirmation = false
                    onChangeQty(0)
                }) {
                    Text(stringResource(R.string.detail_cancel_line_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * Runde Ecken nur aussen: die Zeilen einer Bestellung bilden zusammen eine
 * Karte, so wie iOS eine `List`-Sektion zeichnet.
 */
private fun cardShape(index: Int, count: Int) =
    androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = if (index == 0) 12.dp else 0.dp,
        topEnd = if (index == 0) 12.dp else 0.dp,
        bottomStart = if (index == count - 1) 12.dp else 0.dp,
        bottomEnd = if (index == count - 1) 12.dp else 0.dp,
    )

// MARK: - Vorschau

internal fun previewLines(): List<OrderLine> = listOf(
    OrderLine(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        tableNumber = 3,
        articleId = "a1",
        nameSnapshot = "Schweinsbraten",
        unitPriceCents = 1_290,
        qty = 2,
        createdAt = Instant.parse("2026-09-12T18:00:00Z"),
        deviceId = "dev",
    ),
    OrderLine(
        id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        tableNumber = 3,
        articleId = "a2",
        nameSnapshot = "Grüner Veltliner 1/8",
        unitPriceCents = 320,
        qty = 4,
        createdAt = Instant.parse("2026-09-12T18:02:00Z"),
        deviceId = "dev",
    ),
    OrderLine(
        id = UUID.fromString("00000000-0000-0000-0000-000000000003"),
        tableNumber = 3,
        articleId = "a3",
        nameSnapshot = "Liptauer mit Bauernbrot",
        unitPriceCents = 680,
        qty = 1,
        createdAt = Instant.parse("2026-09-12T18:05:00Z"),
        deviceId = "dev",
        note = "ohne Zwiebel, scharf",
    ),
)

@PreviewLightDark
@Composable
private fun TableDetailPreview() {
    KassaTheme {
        TableDetailScreen(
            tableNumber = 3,
            lines = previewLines(),
            onBack = {},
            onChangeQty = { _, _ -> },
            onAddArticles = {},
            onSettle = {},
        )
    }
}

@Preview(name = "Tisch leer", showBackground = true)
@Composable
private fun TableDetailEmptyPreview() {
    KassaTheme {
        TableDetailScreen(
            tableNumber = 3,
            lines = emptyList(),
            onBack = {},
            onChangeQty = { _, _ -> },
            onAddArticles = {},
            onSettle = {},
        )
    }
}

@Preview(name = "Tisch grosse Schrift", showBackground = true, fontScale = 1.5f)
@Composable
private fun TableDetailLargeFontPreview() {
    KassaTheme {
        TableDetailScreen(
            tableNumber = 12,
            lines = previewLines(),
            onBack = {},
            onChangeQty = { _, _ -> },
            onAddArticles = {},
            onSettle = {},
        )
    }
}
