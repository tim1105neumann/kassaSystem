package at.heuriger.kassa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import at.heuriger.kassa.ui.theme.KassaTheme

/**
 * Tippbar ohne Material-Ripple.
 *
 * Der Ripple ist das auffaelligste Material-Erkennungszeichen. iOS quittiert
 * einen Druck stattdessen durch Abdunkeln, und genau das macht [pressDimmed]:
 * `indication = null` schaltet den Ripple ab, die Deckkraft uebernimmt die
 * Rueckmeldung.
 *
 * Bewusst ohne Animation — ein Druck soll sofort quittiert werden, und damit
 * gibt es auch nichts, was „Bewegung reduzieren" abschalten muesste.
 */
@Composable
private fun Modifier.pressDimmed(
    enabled: Boolean,
    role: Role,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val dim = when {
        !enabled -> 0.4f
        pressed -> 0.6f
        else -> 1f
    }
    return this
        .alpha(dim)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

/**
 * Abschnittsueberschrift einer Formularliste — iOS setzt sie klein, grau und in
 * Versalien ueber die Gruppe.
 */
@Composable
fun KassaSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = KassaTheme.typography.sectionHeader,
        color = KassaTheme.colors.secondaryLabel,
        modifier = modifier.padding(
            start = KassaTheme.dimens.screenPadding,
            end = KassaTheme.dimens.screenPadding,
            bottom = 6.dp,
        ),
    )
}

/**
 * Eingabefeld in iOS-Anmutung: flaches Feld auf der Kachelfarbe, kein
 * Material-Unterstrich und kein schwebendes Label.
 *
 * [label] ist die Beschriftung fuer TalkBack. Sie ist noetig, weil das Feld
 * seinen Namen sonst nur solange traegt, wie der Platzhalter sichtbar ist — nach
 * der ersten Eingabe waere es fuer einen Screenreader ein namenloses Feld.
 */
@Composable
fun KassaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /**
     * Was beim Bestaetigen auf der Tastatur passiert — das Pendant zu SwiftUIs
     * `.onSubmit`. Die Einstellungen brauchen es: die Server-Adresse wird erst
     * uebernommen, wenn jemand das Feld abschliesst, nicht bei jedem Zeichen.
     */
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /**
     * Transparent setzen, wenn das Feld in einer Formulargruppe steht — die
     * Karte darunter bringt dann die Flaeche mit, und zwei uebereinanderliegende
     * Rundungen fallen weg.
     */
    containerColor: Color = KassaTheme.colors.surface,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = dimens.minTouchTarget)
            .background(containerColor, RoundedCornerShape(dimens.controlRadius))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { contentDescription = label },
        textStyle = KassaTheme.typography.body.copy(color = colors.label),
        singleLine = true,
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = KassaTheme.typography.body,
                        color = colors.tertiaryLabel,
                    )
                }
                inner()
            }
        },
    )
}

/** Ausgefuellter Knopf in Akzentfarbe — das Pendant zu `.borderedProminent`. */
@Composable
fun KassaFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * Mindest-, nicht Festhoehe: waechst die Systemschrift, waechst der Knopf
     * mit, statt seine Beschriftung zu beschneiden.
     */
    minHeight: Dp = KassaTheme.dimens.buttonHeight,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens

    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .pressDimmed(enabled = enabled, role = Role.Button, onClick = onClick)
            .background(colors.accent, RoundedCornerShape(dimens.controlRadius))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = KassaTheme.typography.headline,
            color = colors.onAccent,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Symbolknopf der Navigationsleiste. Die Flaeche ist auf 48dp
 * aufgezogen, auch wenn das Symbol kleiner ist — Android verlangt 48dp, und ein
 * 24dp-Symbol allein waere zu klein zum Treffen.
 */
@Composable
fun KassaBarButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val dimens = KassaTheme.dimens
    Box(
        modifier = modifier
            .size(dimens.minTouchTarget)
            .pressDimmed(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = KassaTheme.colors.accent,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Navigationsleiste mit grossem Titel, wie iOS sie oben auf einem Stapel zeigt.
 *
 * Flach und ohne Schatten: die Material-`TopAppBar` traegt bei Scroll eine
 * abgesetzte Flaeche, und genau diese Kante ist es, die eine App sofort als
 * Android-App ausweist.
 */
@Composable
fun KassaNavBar(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val dimens = KassaTheme.dimens

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KassaTheme.colors.groupedBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            leading()
            trailing()
        }
        Text(
            text = title,
            style = KassaTheme.typography.largeTitle,
            color = KassaTheme.colors.label,
            modifier = Modifier.padding(
                start = dimens.screenPadding,
                end = dimens.screenPadding,
                top = 4.dp,
                bottom = 8.dp,
            ),
        )
    }
}

/**
 * Getoenter Knopf — das Pendant zu `.buttonStyle(.bordered)`.
 *
 * Kein Rahmen, sondern eine schwach eingefaerbte Flaeche mit Akzentschrift:
 * genau so zeichnet iOS diesen Stil. Die Flaeche ist [KassaColors.accent] mit
 * 12 % Deckkraft, die Schrift der volle Akzent — auf dem hellen Untergrund
 * bleibt der Kontrast damit derselbe wie bei Akzentschrift auf der Kachel.
 *
 * [minHeight] ist 60dp, weil die Vorlage ihre Aktionsknoepfe so setzt; mit
 * `defaultMinSize` waechst der Knopf bei grosser Systemschrift trotzdem mit.
 */
@Composable
fun KassaTintedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    minHeight: Dp = 60.dp,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .pressDimmed(enabled = enabled, role = Role.Button, onClick = onClick)
            .background(colors.accent.copy(alpha = 0.12f), RoundedCornerShape(dimens.controlRadius))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.clearAndSetSemantics {
                        this.contentDescription = contentDescription
                        this.role = Role.Button
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) {
            content()
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = text,
                    style = KassaTheme.typography.headline,
                    color = colors.accent,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Die feste Aktionsleiste am unteren Rand — iOS' `.background(.bar)`.
 *
 * Bewusst *ausserhalb* der Liste und nicht als deren letztes Element: Summe und
 * „Kassieren" muessen erreichbar bleiben, ohne dass jemand erst ans Ende einer
 * langen Bestellung scrollt.
 */
@Composable
fun KassaBottomBar(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(KassaTheme.dimens.hairline)
                .background(KassaTheme.colors.separator)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(KassaTheme.colors.surface)
                .padding(KassaTheme.dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * Segmentierter Umschalter — iOS' `.pickerStyle(.segmented)`.
 *
 * [label] benennt die Gruppe fuer TalkBack („Kategorie", „Umfang"); ohne ihn
 * waeren zwei Knoepfe zu hoeren, aber nicht, worueber sie entscheiden. Die
 * Auswahl laeuft ueber `selectable` mit [Role.Tab] statt ueber `clickable`, damit
 * „ausgewaehlt" auch angesagt wird und nicht nur farblich erscheint.
 *
 * Der Gruppenname steht bewusst **an jedem Segment** und nicht nur am Rahmen
 * darum. Am Geraet nachgesehen: `selectable` fasst den Text darunter nicht zu
 * einem Knoten zusammen, der TalkBack-Halt heisst dann schlicht nichts und
 * meldet nur „ausgewaehlt". Mit eigener Beschreibung liest er „Kategorie,
 * Speisen, ausgewaehlt".
 */
@Composable
fun KassaSegmentedControl(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KassaTheme.colors
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.label.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
            .padding(2.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val spoken = "$label, $option"
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 40.dp)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (!selected) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(index)
                        }
                    }
                    .background(
                        if (selected) colors.surface else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = KassaTheme.typography.subheadline.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (selected) colors.label else colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    // Der Gruppenname sitzt am Text und nicht am `selectable`
                    // darum: der bekommt seine eigene Beschreibung nicht in
                    // denselben Knoten, und TalkBack laese sonst „Speisen"
                    // zweimal.
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = spoken
                    },
                )
            }
        }
    }
}

/**
 * Runder Mengenknopf, 52dp wie in der Vorlage — deutlich ueber den 48dp, die
 * Android mindestens verlangt. Am Tisch wird er im Stehen und oft einhaendig
 * getroffen.
 *
 * Die Haptik sitzt hier und nicht beim Aufrufer: ein Mengenknopf ohne
 * Rueckmeldung fuehlt sich an, als haette er nicht ausgeloest, und dann wird ein
 * zweites Mal gedrueckt.
 */
@Composable
fun KassaStepperButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val colors = KassaTheme.colors

    Box(
        modifier = modifier
            .size(52.dp)
            .pressDimmed(enabled = enabled, role = Role.Button, onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            })
            .background(colors.accent.copy(alpha = 0.12f), CircleShape)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Grosser Betrag, der lieber schrumpft als abgeschnitten zu werden — das
 * Pendant zu `minimumScaleFactor`. [maxFontSize] ist die Wunschgroesse aus der
 * Vorlage, [minFontSize] die Untergrenze.
 */
@Composable
fun KassaAmount(
    text: String,
    maxFontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = KassaTheme.colors.label,
    minFontSize: TextUnit = maxFontSize * 0.5f,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = KassaTheme.typography.amount.copy(
            color = color,
            fontWeight = FontWeight.Black,
            fontSize = maxFontSize,
        ),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(
            minFontSize = minFontSize,
            maxFontSize = maxFontSize,
            stepSize = 1.sp,
        ),
    )
}

/**
 * Hinweiszeile in Signalfarbe — „Aufstellung liegt in der Kueche", „Es fehlen
 * noch 2,00 €". Symbol plus Text auf der Kachelflaeche, als ein Stueck fuer den
 * Screenreader.
 */
@Composable
fun KassaNoticeRow(
    icon: ImageVector,
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                KassaTheme.colors.groupedBackground,
                RoundedCornerShape(KassaTheme.dimens.controlRadius),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = text },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(text = text, style = KassaTheme.typography.headline, color = tint)
    }
}

/**
 * Leerzustand mit Symbol, Titel und Erklaerung — das Pendant zu
 * `ContentUnavailableView`.
 */
@Composable
fun KassaEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(KassaTheme.dimens.screenPadding * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = KassaTheme.colors.tertiaryLabel,
            modifier = Modifier.size(52.dp),
        )
        Text(
            text = title,
            style = KassaTheme.typography.headline,
            color = KassaTheme.colors.label,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = KassaTheme.typography.subheadline,
            color = KassaTheme.colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Kopfzeile eines Blattes, das von unten hereinfaehrt — iOS' `.inline`-Titel mit
 * „Fertig" rechts.
 *
 * Bewusst nicht [KassaNavBar]: ein Blatt traegt keinen grossen Titel und keinen
 * Rueckweg, sondern einen Abschluss. Der Titel steht mittig, damit die Anmutung
 * dieselbe ist wie auf dem iPhone daneben.
 */
@Composable
fun KassaSheetHeader(
    title: String,
    doneLabel: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = KassaTheme.dimens

    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = dimens.minTouchTarget)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = KassaTheme.typography.headline,
            color = KassaTheme.colors.label,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 72.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .defaultMinSize(minWidth = dimens.minTouchTarget, minHeight = dimens.minTouchTarget)
                .pressDimmed(enabled = true, role = Role.Button, onClick = onDone),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = doneLabel,
                style = KassaTheme.typography.headline,
                color = KassaTheme.colors.accent,
            )
        }
    }
}

/**
 * Eine Formulargruppe: eine gerundete Karte, in der die Zeilen zusammenstehen —
 * iOS' `Section` in einer `Form`.
 */
@Composable
fun KassaFormGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .padding(horizontal = KassaTheme.dimens.screenPadding)
            .fillMaxWidth()
            .background(
                KassaTheme.colors.surface,
                RoundedCornerShape(KassaTheme.dimens.controlRadius),
            ),
        content = content,
    )
}

/**
 * Fussnote unter einer Gruppe — iOS' `footer`. Klein, grau, am Seitenrand
 * eingerueckt wie die Abschnittsueberschrift darueber.
 */
@Composable
fun KassaFootnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = KassaTheme.typography.caption,
        color = KassaTheme.colors.secondaryLabel,
        modifier = modifier.padding(
            start = KassaTheme.dimens.screenPadding,
            end = KassaTheme.dimens.screenPadding,
            top = 6.dp,
        ),
    )
}

/**
 * Zeile „Beschriftung … Wert" — das Pendant zu `LabeledContent`.
 *
 * Beides zusammen als ein Halt fuer TalkBack: „Status, Verbunden" ist eine
 * Aussage, zwei getrennte Textstuecke waeren zwei Wischgesten.
 */
@Composable
fun KassaLabeledRow(
    label: String,
    modifier: Modifier = Modifier,
    spokenValue: String? = null,
    value: @Composable () -> Unit,
) {
    val spoken = spokenValue?.let { "$label, $it" }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = KassaTheme.dimens.minTouchTarget)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .then(
                if (spoken != null) {
                    Modifier.clearAndSetSemantics { contentDescription = spoken }
                } else {
                    Modifier
                }
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = KassaTheme.typography.body,
            color = KassaTheme.colors.label,
            modifier = Modifier.weight(1f),
        )
        value()
    }
}

/** Trennlinie zwischen zwei Listenzeilen, links eingerueckt wie auf iOS. */
@Composable
fun KassaRowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(start = KassaTheme.dimens.screenPadding)
            .fillMaxWidth()
            .height(KassaTheme.dimens.hairline)
            .background(KassaTheme.colors.separator)
    )
}
