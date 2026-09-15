package at.heuriger.kassa.ui.report

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.heuriger.kassa.R
import at.heuriger.kassa.domain.ArticleCategory
import at.heuriger.kassa.ui.components.KassaAmount
import at.heuriger.kassa.ui.components.KassaEmptyState
import at.heuriger.kassa.ui.components.KassaFootnote
import at.heuriger.kassa.ui.components.KassaFormGroup
import at.heuriger.kassa.ui.components.KassaLabeledRow
import at.heuriger.kassa.ui.components.KassaRowDivider
import at.heuriger.kassa.ui.components.KassaSectionHeader
import at.heuriger.kassa.ui.components.KassaSheetHeader
import at.heuriger.kassa.ui.theme.KassaTheme
import at.heuriger.kassa.wire.BusinessDay
import at.heuriger.kassa.wire.DayReportDto
import at.heuriger.kassa.wire.Money
import at.heuriger.kassa.wire.SettlementDto
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/** Was der Bericht gerade ist: unterwegs, gescheitert oder da. */
sealed interface DayReportState {
    data object Loading : DayReportState
    data class Failed(val message: String) : DayReportState
    data class Loaded(val report: DayReportDto) : DayReportState
}

/**
 * Tagesabschluss. Spiegel von `DayReportView.swift`.
 *
 * Zustandslos: Datum und Ladezustand kommen herein, die Datumsauswahl geht als
 * Aufruf hinaus. Dadurch zeigt die Vorschau alle Zustaende ohne Server.
 *
 * **Die Zahlen kommen ausschliesslich vom Server** und nicht aus dem lokalen
 * Spiegel. Der Spiegel kennt nur, was dieses Geraet gesehen hat; der
 * Tagesabschluss muss aber alle Geraete zusammenzaehlen. Ohne Netz gibt es
 * deshalb keinen Bericht, sondern eine Meldung.
 *
 * [selectedDateMillis] ist der Wert, mit dem `DatePicker` rechnet: Mitternacht
 * **UTC** des gewaehlten Kalendertags. Der *Betriebstag* daraus wird nicht hier
 * gebildet, sondern vom Aufrufer ueber [BusinessDay.day] — die Verschiebung um
 * den Cutoff ist Vertragslogik und gehoert nicht in die Oberflaeche.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayReportScreen(
    selectedDateMillis: Long,
    cutoffHour: Int,
    state: DayReportState,
    onSelectDate: (Long) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens
    var showPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.groupedBackground),
    ) {
        KassaSheetHeader(
            title = stringResource(R.string.title_day_report),
            doneLabel = stringResource(R.string.action_done),
            onDone = onDone,
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "day") {
                Column {
                    KassaFormGroup {
                        BusinessDayRow(
                            selectedDateMillis = selectedDateMillis,
                            onClick = { showPicker = true },
                        )
                    }
                    KassaFootnote(stringResource(R.string.report_cutoff_footnote, cutoffHour))
                }
            }

            when (state) {
                DayReportState.Loading -> item(key = "loading") {
                    val spoken = stringResource(R.string.report_a11y_loading)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(dimens.screenPadding)
                            .clearAndSetSemantics { contentDescription = spoken },
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                }

                is DayReportState.Failed -> item(key = "failed") {
                    KassaEmptyState(
                        icon = Icons.Outlined.CloudOff,
                        title = stringResource(R.string.report_error_title),
                        message = state.message,
                    )
                }

                is DayReportState.Loaded -> reportSections(state.report)
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDateMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let(onSelectDate)
                    showPicker = false
                }) {
                    Text(stringResource(R.string.dialog_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * „Betriebstag … 13.09.2026" mit Kalendersymbol. Ein Knopf, kein Textfeld: der
 * Tag wird ausgewaehlt und nicht getippt.
 */
@Composable
private fun BusinessDayRow(selectedDateMillis: Long, onClick: () -> Unit) {
    val colors = KassaTheme.colors
    val label = stringResource(R.string.report_business_day)
    val formatted = formatDate(selectedDateMillis)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = KassaTheme.dimens.minTouchTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = "$label, $formatted" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = KassaTheme.typography.body,
            color = colors.label,
            modifier = Modifier.weight(1f),
        )
        Text(text = formatted, style = KassaTheme.typography.body, color = colors.accent)
        Icon(
            imageVector = Icons.Filled.CalendarMonth,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(22.dp),
        )
    }
}

// MARK: - Abschnitte

/**
 * Die Abschnitte des Berichts als Eintraege *derselben* Liste — nicht als
 * verschachtelte Spalten. An einem vollen Betriebsabend stehen unten leicht
 * hundert Kassiervorgaenge, und die sollen erst gebaut werden, wenn jemand so
 * weit scrollt.
 */
private fun LazyListScope.reportSections(report: DayReportDto) {
    item(key = "total") { TotalSection(report) }

    if (report.byCategory.isNotEmpty()) {
        item(key = "categories") {
            Section(titleRes = R.string.report_section_categories) {
                report.byCategory.forEachIndexed { index, entry ->
                    if (index > 0) KassaRowDivider()
                    AmountRow(
                        label = categoryTitle(entry.category),
                        amount = Money(entry.totalCents),
                    )
                }
            }
        }
    }

    if (report.topArticles.isNotEmpty()) {
        item(key = "top") {
            Section(titleRes = R.string.report_section_top) {
                report.topArticles.forEachIndexed { index, entry ->
                    if (index > 0) KassaRowDivider()
                    AmountRow(
                        label = stringResource(R.string.report_top_line, entry.qty, entry.name),
                        amount = Money(entry.totalCents),
                    )
                }
            }
        }
    }

    if (report.settlements.isNotEmpty()) {
        item(key = "settlements") {
            Section(titleRes = R.string.report_section_settlements) {
                report.settlements.forEachIndexed { index, settlement ->
                    if (index > 0) KassaRowDivider()
                    SettlementRow(settlement)
                }
            }
        }
    }
}

@Composable
private fun TotalSection(report: DayReportDto) {
    val colors = KassaTheme.colors
    val subtitle = stringResource(
        R.string.report_settlement_count,
        report.settlementCount,
        report.businessDay,
    )
    val spoken = stringResource(R.string.report_a11y_total, report.total.formatted, subtitle)

    Section(titleRes = R.string.report_section_total) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .clearAndSetSemantics { contentDescription = spoken },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 48sp wie in der Vorlage; [KassaAmount] schrumpft, statt eine
            // vierstellige Summe zu beschneiden.
            KassaAmount(text = report.total.formatted, maxFontSize = 48.sp, minFontSize = 24.sp)
            Text(
                text = subtitle,
                style = KassaTheme.typography.subheadline,
                color = colors.secondaryLabel,
            )
        }

        // Die grosse Zahl bleibt der Warenumsatz — nur so geht sie mit der
        // Kategorie-Aufschluesselung darunter zusammen. Ohne Trinkgeld waeren die
        // beiden Zeilen bloss zwei Wiederholungen derselben Summe.
        if (report.tipCents > 0) {
            KassaRowDivider()
            AmountRow(label = stringResource(R.string.report_tip), amount = report.tip)
            KassaRowDivider()
            AmountRow(
                label = stringResource(R.string.report_grand_total),
                amount = report.grandTotal,
                bold = true,
            )
        }
    }
}

@Composable
private fun SettlementRow(settlement: SettlementDto) {
    val colors = KassaTheme.colors
    val title = stringResource(R.string.table_title, settlement.tableNumber)
    val time = formatTime(settlement.paidAt)
    val tipLine = if (settlement.tipCents > 0) {
        stringResource(R.string.report_settlement_tip, settlement.tip.formatted)
    } else {
        null
    }
    val spoken = stringResource(
        R.string.report_a11y_row,
        listOfNotNull(title, time, tipLine).joinToString(", "),
        settlement.total.formatted,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, style = KassaTheme.typography.body, color = colors.label)
            Text(text = time, style = KassaTheme.typography.caption, color = colors.secondaryLabel)
            if (tipLine != null) {
                Text(
                    text = tipLine,
                    style = KassaTheme.typography.caption,
                    color = colors.secondaryLabel,
                )
            }
        }
        Text(
            text = settlement.total.formatted,
            style = KassaTheme.typography.amount,
            color = colors.label,
            maxLines = 1,
        )
    }
}

/** Ueberschrift plus Karte — die Bauform jeder Gruppe in diesem Bericht. */
@Composable
private fun Section(@StringRes titleRes: Int, content: @Composable ColumnScope.() -> Unit) {
    Column {
        KassaSectionHeader(stringResource(titleRes))
        KassaFormGroup(content = content)
    }
}

@Composable
private fun AmountRow(label: String, amount: Money, bold: Boolean = false) {
    KassaLabeledRow(label = label, spokenValue = amount.formatted) {
        Text(
            text = amount.formatted,
            style = KassaTheme.typography.amount.copy(
                fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            ),
            color = KassaTheme.colors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - Formatierung

/** Die CSV schreibt „Getraenke" ohne Umlaut — angezeigt wird mit. */
@Composable
private fun categoryTitle(raw: String): String = when (ArticleCategory.fromRawValue(raw)) {
    ArticleCategory.SPEISEN -> stringResource(R.string.category_speisen)
    ArticleCategory.GETRAENKE -> stringResource(R.string.category_getraenke)
    // Eine Kategorie, die dieser Client noch nicht kennt, wird roh gezeigt statt
    // verschluckt: sonst fehlte Umsatz im Bericht ohne jeden Hinweis.
    null -> raw
}

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.GERMAN)

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)

/**
 * `DatePicker` rechnet in Mitternacht **UTC** — deshalb wird der Wert auch in
 * UTC wieder zum Kalendertag gemacht und nicht in Wien. In Wien gelesen waere
 * der 13. bis 02:00 noch der 12.
 */
private fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().format(DATE_FORMAT)

/** Bezahlzeiten kommen als UTC herein und werden in Wien gelesen. */
private fun formatTime(instant: Instant): String =
    instant.atZone(BusinessDay.TIME_ZONE).format(TIME_FORMAT)

// MARK: - Vorschau

private const val PREVIEW_MILLIS = 1_789_257_600_000L // 2026-09-13, Mitternacht UTC

private fun previewReport(): DayReportDto = DayReportDto(
    businessDay = "2026-09-13",
    totalCents = 1620,
    settlementCount = 1,
    byCategory = listOf(
        DayReportDto.CategoryTotal(category = "Speisen", totalCents = 1240),
        DayReportDto.CategoryTotal(category = "Getraenke", totalCents = 380),
    ),
    topArticles = listOf(
        DayReportDto.ArticleTotal(
            articleId = "a1",
            name = "Käsekrainer mit Gebäck",
            qty = 2,
            totalCents = 1240,
        ),
        DayReportDto.ArticleTotal(
            articleId = "a2",
            name = "Bier 0,3 l",
            qty = 1,
            totalCents = 380,
        ),
    ),
    settlements = listOf(
        SettlementDto(
            id = UUID.fromString("4EBD60C3-C165-4C96-8B07-E0A71BADB17B"),
            tableNumber = 1,
            totalCents = 1620,
            paidAt = Instant.parse("2026-09-13T14:08:18Z"),
            deviceId = "preview",
            businessDay = "2026-09-13",
            updatedSeq = 282,
            tipCents = 80,
        ),
    ),
    tipCents = 80,
)

@PreviewLightDark
@Composable
private fun DayReportLoadedPreview() {
    KassaTheme {
        DayReportScreen(
            selectedDateMillis = PREVIEW_MILLIS,
            cutoffHour = 6,
            state = DayReportState.Loaded(previewReport()),
            onSelectDate = {},
            onDone = {},
        )
    }
}

@Preview(name = "Tagesabschluss ohne Trinkgeld", showBackground = true)
@Composable
private fun DayReportWithoutTipPreview() {
    KassaTheme {
        DayReportScreen(
            selectedDateMillis = PREVIEW_MILLIS,
            cutoffHour = 6,
            state = DayReportState.Loaded(previewReport().copy(tipCents = 0)),
            onSelectDate = {},
            onDone = {},
        )
    }
}

@Preview(name = "Tagesabschluss leer", showBackground = true)
@Composable
private fun DayReportEmptyPreview() {
    KassaTheme {
        DayReportScreen(
            selectedDateMillis = PREVIEW_MILLIS,
            cutoffHour = 6,
            state = DayReportState.Loaded(
                DayReportDto(
                    businessDay = "2026-09-12",
                    totalCents = 0,
                    settlementCount = 0,
                    byCategory = emptyList(),
                    topArticles = emptyList(),
                    settlements = emptyList(),
                )
            ),
            onSelectDate = {},
            onDone = {},
        )
    }
}

@Preview(name = "Tagesabschluss nicht ladbar", showBackground = true)
@Composable
private fun DayReportFailedPreview() {
    KassaTheme {
        DayReportScreen(
            selectedDateMillis = PREVIEW_MILLIS,
            cutoffHour = 6,
            state = DayReportState.Failed("Keine Verbindung zum Server."),
            onSelectDate = {},
            onDone = {},
        )
    }
}

@Preview(name = "Tagesabschluss grosse Schrift", showBackground = true, fontScale = 1.5f)
@Composable
private fun DayReportLargeFontPreview() {
    KassaTheme {
        DayReportScreen(
            selectedDateMillis = PREVIEW_MILLIS,
            cutoffHour = 6,
            state = DayReportState.Loaded(previewReport()),
            onSelectDate = {},
            onDone = {},
        )
    }
}
