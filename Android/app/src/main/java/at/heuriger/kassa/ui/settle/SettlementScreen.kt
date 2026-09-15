package at.heuriger.kassa.ui.settle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.heuriger.kassa.R
import at.heuriger.kassa.domain.ChangeCalculator
import at.heuriger.kassa.domain.ChangeResult
import at.heuriger.kassa.domain.OrderLine
import at.heuriger.kassa.domain.SettlementSelection
import at.heuriger.kassa.domain.TableTotals
import at.heuriger.kassa.domain.TipCalculator
import at.heuriger.kassa.domain.TipResult
import at.heuriger.kassa.ui.components.KassaAmount
import at.heuriger.kassa.ui.components.KassaBarButton
import at.heuriger.kassa.ui.components.KassaBottomBar
import at.heuriger.kassa.ui.components.KassaFilledButton
import at.heuriger.kassa.ui.components.KassaNavBar
import at.heuriger.kassa.ui.components.KassaNoticeRow
import at.heuriger.kassa.ui.components.KassaRowDivider
import at.heuriger.kassa.ui.components.KassaSegmentedControl
import at.heuriger.kassa.ui.components.KassaStepperButton
import at.heuriger.kassa.ui.components.KassaTextField
import at.heuriger.kassa.ui.components.KassaTintedButton
import at.heuriger.kassa.ui.theme.KassaTheme
import at.heuriger.kassa.wire.Money
import at.heuriger.kassa.wire.SettlementLineSelection
import kotlinx.coroutines.launch

/** Erste Stufe: was wird kassiert. Zweite Stufe: wie wird bezahlt. */
private enum class Stage { SELECT, PAY }

/** „Alles kassieren" oder „Teilbetrag" — der Umschalter der ersten Stufe. */
private enum class Scope { ALL, PARTIAL }

/** Zustand des Druckauftrags. Bewusst kurzlebig, siehe [SettlementScreen]. */
private sealed interface PrintState {
    data object Idle : PrintState
    data object Sending : PrintState
    data object Done : PrintState
    data class Failed(val message: String) : PrintState
}

/**
 * Kassieren. Spiegel von `SettlementView.swift`.
 *
 * **Zwei Stufen in einem Bildschirm, nicht zwei Bildschirme.** Der Weg zurueck
 * von „Zahlen" nach „Auswahl" muss die Auswahl unveraendert vorfinden — ein
 * zweites Navigationsziel haette sie entweder verloren oder haette sie durch den
 * Stapel tragen muessen.
 *
 * Auswahl, Umfang und die beiden Eingabefelder liegen in [rememberSaveable] und
 * ueberleben damit Drehung und Prozesstod. [SettlementSelection] ist dafuer
 * `@Parcelize`. Der Druckzustand liegt bewusst in einem einfachen [remember]:
 * „Aufstellung liegt in der Kueche" ist eine Aussage ueber die letzten Sekunden
 * und nach einem Prozesstod schlicht nicht mehr wahr.
 *
 * [onPrintOverview] ist `suspend` und liefert `null` bei Erfolg, sonst die
 * deutsche Meldung. Der Druck geht absichtlich **direkt an den Server** und
 * nicht durch die Offline-Queue: ein Zettel, der zwanzig Minuten spaeter aus dem
 * Drucker kommt, hilft am Tisch niemandem. Ohne Netz sieht der Kellner deshalb
 * lieber sofort eine Meldung.
 */
@Composable
fun SettlementScreen(
    tableNumber: Int,
    lines: List<OrderLine>,
    onBack: () -> Unit,
    onPrintOverview: suspend (List<SettlementLineSelection>) -> String?,
    onConfirm: (selections: List<SettlementLineSelection>, total: Money, tip: Money) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens
    val haptics = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val openLines = TableTotals.openLines(lines)

    var stage by rememberSaveable { mutableStateOf(Stage.SELECT) }
    var mode by rememberSaveable { mutableStateOf(Scope.ALL) }
    var selection by rememberSaveable { mutableStateOf(SettlementSelection()) }
    var paidText by rememberSaveable { mutableStateOf("") }
    var givenText by rememberSaveable { mutableStateOf("") }
    var printState by remember { mutableStateOf<PrintState>(PrintState.Idle) }

    val amount = if (mode == Scope.ALL) {
        TableTotals.openTotal(openLines)
    } else {
        selection.subtotal(openLines)
    }

    // Kassieren und Aufstellung muessen dasselbe zeigen — beide leiten die
    // Positionen an dieser einen Stelle ab.
    val selections = if (mode == Scope.ALL) {
        openLines.map { SettlementLineSelection(lineId = it.id, qty = it.qty) }
    } else {
        selection.requestLines(openLines)
    }

    val paid = Money.parse(paidText)
    val tip = paid?.let { TipCalculator.tip(total = amount, paid = it) }
    // Ohne Eingabe im „Macht"-Feld zahlt der Gast einfach die Rechnung.
    val payable = paid ?: amount
    val given = Money.parse(givenText)
    val change = given?.let { ChangeCalculator.change(total = payable, given = it) }

    val blocked = when (stage) {
        Stage.SELECT -> selections.isEmpty()
        // Leere Felder sind erlaubt: kein Trinkgeld, kein Rueckgeld. Nur ein zu
        // kleiner eingegebener Betrag blockiert.
        Stage.PAY -> tip is TipResult.BelowTotal || change is ChangeResult.NotEnough
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.groupedBackground),
    ) {
        KassaNavBar(
            title = stringResource(R.string.settle_title, tableNumber),
            leading = {
                KassaBarButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                )
            },
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (stage) {
                Stage.SELECT -> SelectionStage(
                    lines = openLines,
                    mode = mode,
                    selection = selection,
                    onModeChange = { mode = it },
                    onSelectionChange = { selection = it },
                )

                Stage.PAY -> PaymentStage(
                    amount = amount,
                    payable = payable,
                    paidText = paidText,
                    givenText = givenText,
                    tip = tip,
                    change = change,
                    onPaidChange = { paidText = it },
                    onGivenChange = { givenText = it },
                    focus = focus,
                )
            }
        }

        KassaBottomBar {
            if (stage == Stage.SELECT) {
                val amountSpoken = stringResource(R.string.settle_a11y_amount, amount.formatted)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics { contentDescription = amountSpoken },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settle_amount),
                        style = KassaTheme.typography.headline.copy(fontSize = 22.sp),
                        color = colors.label,
                    )
                    Box(modifier = Modifier.weight(1f))
                    KassaAmount(text = amount.formatted, maxFontSize = 36.sp)
                }

                when (val state = printState) {
                    PrintState.Idle, PrintState.Sending -> Unit
                    PrintState.Done -> KassaNoticeRow(
                        icon = Icons.Filled.CheckCircle,
                        text = stringResource(R.string.settle_print_done),
                        tint = colors.green,
                    )
                    is PrintState.Failed -> KassaNoticeRow(
                        icon = Icons.Filled.Warning,
                        text = state.message,
                        tint = colors.red,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (stage == Stage.SELECT) {
                    val printing = printState == PrintState.Sending
                    KassaTintedButton(
                        text = stringResource(R.string.settle_print),
                        icon = Icons.Filled.Print,
                        contentDescription = stringResource(R.string.settle_a11y_print),
                        enabled = selections.isNotEmpty() && !printing,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            printState = PrintState.Sending
                            scope.launch {
                                val failure = onPrintOverview(selections)
                                printState = if (failure == null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    PrintState.Done
                                } else {
                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                    PrintState.Failed(failure)
                                }
                            }
                        },
                        content = if (!printing) null else {
                            {
                                CircularProgressIndicator(
                                    color = colors.accent,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        },
                    )
                } else {
                    KassaTintedButton(
                        text = stringResource(R.string.action_back),
                        onClick = { stage = Stage.SELECT },
                        modifier = Modifier.weight(1f),
                    )
                }

                KassaFilledButton(
                    text = stringResource(
                        if (stage == Stage.SELECT) R.string.settle_next else R.string.settle_confirm
                    ),
                    enabled = !blocked,
                    minHeight = 60.dp,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (stage == Stage.SELECT) {
                            focus.clearFocus()
                            stage = Stage.PAY
                            return@KassaFilledButton
                        }
                        if (selections.isEmpty()) return@KassaFilledButton
                        // `amount` bleibt die reine Warensumme, das Trinkgeld
                        // geht daneben mit — sonst ginge die Nachrechnung am
                        // Server nicht mehr gegen die Zeilen auf.
                        val tipOrZero = (tip as? TipResult.Success)?.tip ?: Money.ZERO
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onConfirm(selections, amount, tipOrZero)
                    },
                )
            }
        }
    }
}

// MARK: - Stufe 1: Auswahl

@Composable
private fun SelectionStage(
    lines: List<OrderLine>,
    mode: Scope,
    selection: SettlementSelection,
    onModeChange: (Scope) -> Unit,
    onSelectionChange: (SettlementSelection) -> Unit,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens

    Column(modifier = Modifier.fillMaxSize()) {
        KassaSegmentedControl(
            label = stringResource(R.string.settle_scope),
            options = listOf(
                stringResource(R.string.settle_scope_all),
                stringResource(R.string.settle_scope_partial),
            ),
            selectedIndex = if (mode == Scope.ALL) 0 else 1,
            onSelect = { onModeChange(if (it == 0) Scope.ALL else Scope.PARTIAL) },
            modifier = Modifier
                .padding(horizontal = dimens.screenPadding)
                .padding(bottom = 8.dp),
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = dimens.screenPadding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(lines, key = { _, line -> line.id }) { index, line ->
                Column(
                    modifier = Modifier.background(
                        colors.surface,
                        RoundedCornerShape(
                            topStart = if (index == 0) 12.dp else 0.dp,
                            topEnd = if (index == 0) 12.dp else 0.dp,
                            bottomStart = if (index == lines.size - 1) 12.dp else 0.dp,
                            bottomEnd = if (index == lines.size - 1) 12.dp else 0.dp,
                        ),
                    )
                ) {
                    if (index > 0) KassaRowDivider()
                    if (mode == Scope.ALL) {
                        ReadOnlyLineRow(line)
                    } else {
                        PartialLineRow(
                            line = line,
                            selected = selection.qty(line.id),
                            onChange = { onSelectionChange(selection.set(it, line.id, line.qty)) },
                        )
                    }
                }
            }
        }
    }
}

/** „2× Schweinsbraten … 25,80 €" — bei „Alles kassieren" gibt es nichts zu tippen. */
@Composable
private fun ReadOnlyLineRow(line: OrderLine) {
    val colors = KassaTheme.colors
    val note = line.note?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settle_line, line.qty, line.nameSnapshot),
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

        // Auch beim Kassieren sichtbar: sonst laesst sich am Tisch nicht mehr
        // zuordnen, welche Position welchen Sonderwunsch trug.
        if (note != null) {
            Text(
                text = note,
                style = KassaTheme.typography.subheadline,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Teilbetrag: „%d von %d" plus zwei runde Knoepfe. Die Grenzen 0..qty setzt
 * [SettlementSelection.set] selbst; die Knoepfe werden trotzdem gesperrt, damit
 * ein wirkungsloser Druck gar nicht erst moeglich ist.
 */
@Composable
private fun PartialLineRow(
    line: OrderLine,
    selected: Int,
    onChange: (Int) -> Unit,
) {
    val colors = KassaTheme.colors
    val note = line.note?.takeIf { it.isNotBlank() }
    val rowSpoken = if (note != null) {
        stringResource(
            R.string.settle_a11y_partial_line_note,
            line.nameSnapshot,
            selected,
            line.qty,
            note,
        )
    } else {
        stringResource(
            R.string.settle_a11y_partial_line,
            line.nameSnapshot,
            selected,
            line.qty,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = rowSpoken },
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
                text = stringResource(
                    R.string.detail_unit_price,
                    Money(line.unitPriceCents).formatted,
                ),
                style = KassaTheme.typography.subheadline,
                color = colors.secondaryLabel,
                maxLines = 1,
            )
        }

        // Die Notiz steckt schon in der Zeilenbeschreibung oben, deshalb ohne
        // eigene Semantik.
        if (note != null) {
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
                text = stringResource(R.string.settle_partial_qty, selected, line.qty),
                style = KassaTheme.typography.amount.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            KassaStepperButton(
                icon = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.detail_a11y_decrement),
                enabled = selected > 0,
                onClick = { onChange(selected - 1) },
            )
            KassaStepperButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.detail_a11y_increment),
                enabled = selected < line.qty,
                onClick = { onChange(selected + 1) },
            )
        }
    }
}

// MARK: - Stufe 2: Zahlen

@Composable
private fun PaymentStage(
    amount: Money,
    payable: Money,
    paidText: String,
    givenText: String,
    tip: TipResult?,
    change: ChangeResult?,
    onPaidChange: (String) -> Unit,
    onGivenChange: (String) -> Unit,
    focus: FocusManager,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens
    val payableSpoken = stringResource(R.string.settle_a11y_payable, amount.formatted)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            modifier = Modifier.clearAndSetSemantics { contentDescription = payableSpoken },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.settle_payable),
                style = KassaTheme.typography.headline,
                color = colors.secondaryLabel,
            )
            KassaAmount(text = amount.formatted, maxFontSize = 44.sp)
        }

        TipInput(
            amount = amount,
            paidText = paidText,
            tip = tip,
            onPaidChange = onPaidChange,
            focus = focus,
        )

        ChangeInput(
            payable = payable,
            givenText = givenText,
            change = change,
            onGivenChange = onGivenChange,
            focus = focus,
        )
    }
}

@Composable
private fun TipInput(
    amount: Money,
    paidText: String,
    tip: TipResult?,
    onPaidChange: (String) -> Unit,
    focus: FocusManager,
) {
    val colors = KassaTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settle_tip_prompt),
                style = KassaTheme.typography.headline,
                color = colors.label,
            )
            KassaTextField(
                value = paidText,
                onValueChange = onPaidChange,
                // Der Platzhalter ist der Rechnungsbetrag ohne Waehrungszeichen:
                // so steht schon da, was „passend" hiesse.
                placeholder = amount.formattedPlain,
                label = stringResource(R.string.settle_a11y_tip_field),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.weight(1f),
            )
            if (tip is TipResult.Success && tip.tip.cents > 0) {
                val tipSpoken = stringResource(R.string.settle_a11y_tip, tip.tip.formatted)
                Column(
                    modifier = Modifier
                        .widthIn(min = 88.dp)
                        .clearAndSetSemantics { contentDescription = tipSpoken },
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = stringResource(R.string.settle_tip),
                        style = KassaTheme.typography.caption,
                        color = colors.secondaryLabel,
                    )
                    Text(
                        text = tip.tip.formatted,
                        style = KassaTheme.typography.amount.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colors.green,
                        maxLines = 1,
                    )
                }
            }
        }

        if (tip is TipResult.BelowTotal) {
            KassaNoticeRow(
                icon = Icons.Filled.Warning,
                text = stringResource(R.string.settle_tip_below),
                tint = colors.red,
            )
        }

        QuickPicks(
            values = TipCalculator.quickTotals(amount),
            exact = amount,
            onPick = {
                onPaidChange(it.formattedPlain)
                focus.clearFocus()
            },
        )
    }
}

@Composable
private fun ChangeInput(
    payable: Money,
    givenText: String,
    change: ChangeResult?,
    onGivenChange: (String) -> Unit,
    focus: FocusManager,
) {
    val colors = KassaTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settle_given),
            style = KassaTheme.typography.headline,
            color = colors.label,
        )
        KassaTextField(
            value = givenText,
            onValueChange = onGivenChange,
            placeholder = stringResource(R.string.settle_given_placeholder),
            label = stringResource(R.string.settle_a11y_given_field),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
        )

        QuickPicks(
            values = ChangeCalculator.quickAmounts(payable),
            exact = payable,
            onPick = {
                onGivenChange(it.formattedPlain)
                focus.clearFocus()
            },
        )

        when (change) {
            null -> Unit
            is ChangeResult.Success -> {
                val changeSpoken = stringResource(
                    R.string.settle_a11y_change,
                    change.change.formatted,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                        .clearAndSetSemantics { contentDescription = changeSpoken },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settle_change),
                        style = KassaTheme.typography.headline,
                        color = colors.secondaryLabel,
                    )
                    KassaAmount(
                        text = change.change.formatted,
                        maxFontSize = 60.sp,
                        minFontSize = 24.sp,
                        color = colors.green,
                    )
                }
            }

            is ChangeResult.NotEnough -> KassaNoticeRow(
                icon = Icons.Filled.Warning,
                text = stringResource(
                    R.string.settle_change_missing,
                    change.missing.formatted,
                ),
                tint = colors.red,
            )
        }
    }
}

/**
 * Schnellwahl. Der Knopf, der genau dem Betrag entspricht, heisst „passend" —
 * derselbe Betrag ein zweites Mal als Zahl waere im Stress nur eine Zeile
 * Suchen mehr.
 *
 * [FlowRow] statt eines Rasters: die Liste steht in einer scrollenden Spalte,
 * und ein `LazyVerticalGrid` darf dort nicht liegen (unbeschraenkte Hoehe).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickPicks(
    values: List<Money>,
    exact: Money,
    onPick: (Money) -> Unit,
) {
    if (values.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    val exactLabel = stringResource(R.string.settle_quick_exact)

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 3,
    ) {
        for (value in values) {
            val label = if (value == exact) exactLabel else value.formatted
            KassaTintedButton(
                text = label,
                minHeight = 52.dp,
                modifier = Modifier.weight(1f),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onPick(value)
                },
            )
        }
    }
}

// MARK: - Vorschau

private fun previewLines(): List<OrderLine> =
    at.heuriger.kassa.ui.table.previewLines()

@PreviewLightDark
@Composable
private fun SettlementSelectPreview() {
    KassaTheme {
        SettlementScreen(
            tableNumber = 3,
            lines = previewLines(),
            onBack = {},
            onPrintOverview = { null },
            onConfirm = { _, _, _ -> },
        )
    }
}

@Preview(name = "Kassieren grosse Schrift", showBackground = true, fontScale = 1.5f)
@Composable
private fun SettlementLargeFontPreview() {
    KassaTheme {
        SettlementScreen(
            tableNumber = 3,
            lines = previewLines(),
            onBack = {},
            onPrintOverview = { null },
            onConfirm = { _, _, _ -> },
        )
    }
}

@Preview(name = "Kassieren ohne Zeilen", showBackground = true)
@Composable
private fun SettlementEmptyPreview() {
    KassaTheme {
        SettlementScreen(
            tableNumber = 3,
            lines = emptyList(),
            onBack = {},
            onPrintOverview = { null },
            onConfirm = { _, _, _ -> },
        )
    }
}
