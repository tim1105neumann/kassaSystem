package at.heuriger.kassa.ui.articles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.heuriger.kassa.R
import at.heuriger.kassa.data.BookingItem
import at.heuriger.kassa.data.db.ArticleEntity
import at.heuriger.kassa.domain.ArticleCategory
import at.heuriger.kassa.ui.components.KassaAmount
import at.heuriger.kassa.ui.components.KassaBarButton
import at.heuriger.kassa.ui.components.KassaBottomBar
import at.heuriger.kassa.ui.components.KassaEmptyState
import at.heuriger.kassa.ui.components.KassaFilledButton
import at.heuriger.kassa.ui.components.KassaNavBar
import at.heuriger.kassa.ui.components.KassaSegmentedControl
import at.heuriger.kassa.ui.components.KassaTextField
import at.heuriger.kassa.ui.theme.KassaTheme
import at.heuriger.kassa.wire.Money

/**
 * Die Artikelauswahl. Spiegel von `ArticlePickerView.swift`.
 *
 * **Der Entwurf ist reiner Bildschirmzustand.** Getippt wird auf Kacheln, bis
 * die Runde stimmt; gespeichert wird erst beim Buchen, und dann alles auf
 * einmal. Der Grund ist fachlich: jeder Tipp als eigene Bestellzeile waere
 * dreimal „Ein Achterl" statt einmal „3× Achterl", auf dem Kuechenbon wie in
 * der Zeilenliste des Tisches. Ausserdem laesst sich ein Vertipper so noch
 * zuruecknehmen, ohne dass eine Stornierung durch die Offline-Queue laeuft.
 *
 * [rememberSaveable] traegt den Entwurf ueber einen Prozesstod im Hintergrund —
 * die halb aufgenommene Bestellung am Tisch ist genau das, was man nicht noch
 * einmal erfragen moechte.
 */
@Composable
fun ArticlePickerScreen(
    tableNumber: Int,
    articles: List<ArticleEntity>,
    onBack: () -> Unit,
    onBook: (List<BookingItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens
    val haptics = LocalHapticFeedback.current

    // `Map` statt `SnapshotStateMap`: der unveraenderliche Wert laesst sich in
    // ein Bundle legen, die beobachtbare Map nicht.
    var draft by rememberSaveable { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // Zweite flache Map statt eines Entwurfsobjekts je Artikel: Composes
    // `canBeSavedToBundle` laesst eine `Serializable`-Map durch, das Parceln
    // eigener Werttypen wirft dann `NotSerializableException` — und zwar erst
    // beim Wegschalten der App, nicht beim Entwickeln.
    var notes by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Nur die Artikel-ID: ein offener Dialog uebersteht damit den Prozesstod
    // genauso wie der Entwurf selbst.
    var noteFor by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf(ArticleCategory.SPEISEN) }

    val visible = articles.filter { it.category == category.rawValue }
    val draftTotal = articles.fold(Money.ZERO) { sum, article ->
        val qty = draft[article.id] ?: 0
        if (qty > 0) sum + Money(article.priceCents) * qty else sum
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.groupedBackground),
    ) {
        KassaNavBar(
            title = stringResource(R.string.table_title, tableNumber),
            leading = {
                KassaBarButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                )
            },
        )

        KassaSegmentedControl(
            label = stringResource(R.string.articles_category),
            options = listOf(
                stringResource(R.string.category_speisen),
                stringResource(R.string.category_getraenke),
            ),
            selectedIndex = if (category == ArticleCategory.SPEISEN) 0 else 1,
            onSelect = {
                category = if (it == 0) ArticleCategory.SPEISEN else ArticleCategory.GETRAENKE
            },
            modifier = Modifier
                .padding(horizontal = dimens.screenPadding)
                .padding(bottom = 8.dp),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (visible.isEmpty()) {
                KassaEmptyState(
                    icon = Icons.Outlined.WifiOff,
                    title = stringResource(R.string.articles_empty_title),
                    message = stringResource(R.string.articles_empty_message),
                )
            } else {
                LazyVerticalGrid(
                    // Adaptiv wie im Tischraster: drei Spalten auf dem Telefon,
                    // mehr auf einem Tablet, ohne Sonderfall im Code.
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = dimens.screenPadding,
                        end = dimens.screenPadding,
                        bottom = 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
                    verticalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
                ) {
                    items(visible, key = { it.id }) { article ->
                        ArticleTile(
                            article = article,
                            count = draft[article.id] ?: 0,
                            note = notes[article.id],
                            onIncrement = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                draft = draft + (article.id to (draft[article.id] ?: 0) + 1)
                            },
                            onDecrement = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                val next = (draft[article.id] ?: 0) - 1
                                if (next <= 0) {
                                    draft = draft - article.id
                                    // Sonst taucht die Notiz beim naechsten Antippen
                                    // desselben Artikels als Geist wieder auf.
                                    notes = notes - article.id
                                } else {
                                    draft = draft + (article.id to next)
                                }
                            },
                            onEditNote = { noteFor = article.id },
                        )
                    }
                }
            }
        }

        KassaBottomBar {
            val draftSpoken = stringResource(
                R.string.articles_a11y_draft_total,
                draftTotal.formatted,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = draftSpoken },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.articles_draft),
                    style = KassaTheme.typography.headline.copy(fontSize = 22.sp),
                    color = colors.label,
                )
                Box(modifier = Modifier.weight(1f))
                KassaAmount(text = draftTotal.formatted, maxFontSize = 34.sp)
            }

            KassaFilledButton(
                text = stringResource(R.string.articles_book),
                enabled = draft.isNotEmpty(),
                minHeight = 60.dp,
                onClick = {
                    val items = articles.mapNotNull { article ->
                        val qty = draft[article.id] ?: 0
                        if (qty > 0) BookingItem(article, qty, notes[article.id]) else null
                    }
                    if (items.isEmpty()) return@KassaFilledButton
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    draft = emptyMap()
                    notes = emptyMap()
                    onBook(items)
                },
            )
        }
    }

    // Nach der Artikelliste aufgeloest: ein Artikel, der zwischenzeitlich aus dem
    // Katalog gefallen ist, schliesst den Dialog statt ihn leer zu zeigen.
    val noteArticle = noteFor?.let { id -> articles.firstOrNull { it.id == id } }
    if (noteArticle != null) {
        NoteDialog(
            articleName = noteArticle.name,
            initial = notes[noteArticle.id] ?: "",
            onDismiss = { noteFor = null },
            onConfirm = { text ->
                notes = if (text.isBlank()) notes - noteArticle.id
                else notes + (noteArticle.id to text)
                noteFor = null
            },
        )
    }
}

/**
 * Eine Artikelkachel. Tippen erhoeht die Menge — das ist die haeufigste
 * Handlung und braucht deshalb die ganze Flaeche, nicht einen kleinen Knopf.
 *
 * Der Minusknopf erscheint erst ab Menge 1: davor waere er ein totes
 * Bedienelement, und er verdeckte nur den Preis. Ab Menge 1 wechselt die Kachel
 * auf die Akzentfarbe, damit auf einen Blick zu sehen ist, was im Entwurf
 * liegt, ohne jedes Abzeichen zu lesen.
 *
 * Ab Menge 1 erscheint ausserdem die Notizzeile. Sie steht *in* der Kachel und
 * nicht als zweiter Kreis neben dem Minusknopf: oben rechts sitzt schon das
 * Abzeichen, und zwei runde Knoepfe nebeneinander lassen auf einer Kachel
 * dieser Breite keinen Platz mehr fuer den Preis. Der Normalfall ohne Notiz
 * kostet so weiterhin keinen zusaetzlichen Tipp.
 */
@Composable
private fun ArticleTile(
    article: ArticleEntity,
    count: Int,
    note: String?,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onEditNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens
    val chosen = count > 0
    val shape = RoundedCornerShape(dimens.cornerRadius)
    val price = Money(article.priceCents)

    val tileSpoken = when {
        chosen && !note.isNullOrBlank() -> stringResource(
            R.string.articles_a11y_tile_chosen_note,
            article.name,
            price.formatted,
            count,
            note,
        )

        chosen -> stringResource(
            R.string.articles_a11y_tile_chosen,
            article.name,
            price.formatted,
            count,
        )

        else -> stringResource(R.string.articles_a11y_tile, article.name, price.formatted)
    }
    val decrementSpoken = stringResource(R.string.articles_a11y_decrement, article.name)
    val noteSpoken = stringResource(R.string.articles_a11y_note, article.name)

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 108.dp)
                .background(if (chosen) colors.accent else colors.surface, shape)
                .then(
                    if (chosen) Modifier
                    else Modifier.border(dimens.hairline, colors.separator, shape)
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = tileSpoken
                    role = Role.Button
                }
                .clickableNoRipple(onIncrement)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = article.name,
                style = KassaTheme.typography.headline,
                color = if (chosen) colors.onAccent else colors.label,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                // Platz fuer das Zaehler-Abzeichen oben rechts.
                modifier = Modifier.padding(end = if (chosen) 40.dp else 0.dp),
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                text = price.formatted,
                style = KassaTheme.typography.amount.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = if (chosen) colors.onAccent else colors.secondaryLabel,
                maxLines = 1,
            )

            if (chosen) {
                val hasNote = !note.isNullOrBlank()
                // Gedaempft, aber in derselben Farbfamilie: ein Grau aus dem
                // Palettensatz waere auf der Akzentflaeche kaum lesbar.
                val noteColor =
                    if (hasNote) colors.onAccent else colors.onAccent.copy(alpha = 0.75f)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Laeuft links am Minus-Overlay vorbei; das Polster liegt
                        // vor dem `clickable`, damit sich die Trefferflaechen
                        // nicht ueberlappen.
                        .padding(end = 50.dp)
                        // Eigener Knopf, kein blosser Text: ein Fehlgriff landet
                        // sonst auf der Kachelflaeche und erhoeht die Menge.
                        .defaultMinSize(minHeight = dimens.minTouchTarget)
                        .semantics(mergeDescendants = true) {
                            contentDescription = noteSpoken
                            role = Role.Button
                        }
                        .clickableNoRipple(onEditNote),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        tint = colors.onAccent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        // Ohne Notiz steht hier die Aufforderung, sonst waere die
                        // Zeile eine unbeschriftete Trefferflaeche.
                        text = note?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.articles_note),
                        style = KassaTheme.typography.subheadline,
                        color = noteColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (chosen) {
            // Abzeichen: reine Anzeige, der Zaehler steht schon in der
            // Kachelbeschreibung. Deshalb ohne eigene Semantik.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .defaultMinSize(minWidth = 34.dp, minHeight = 34.dp)
                    .background(colors.onAccent, CircleShape)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$count",
                    style = KassaTheme.typography.amount.copy(fontWeight = FontWeight.Bold),
                    color = colors.accent,
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(dimens.minTouchTarget)
                    .background(colors.onAccent, CircleShape)
                    .semantics {
                        contentDescription = decrementSpoken
                        role = Role.Button
                    }
                    .clickableNoRipple(onDecrement),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

// MARK: - Notiz

/**
 * Der Server kuerzt still auf diese Laenge, statt eine laengere Notiz mit 400
 * abzulehnen. Das Feld begrenzt deshalb genauso: ein 4xx wuerde die ganze
 * Buchungsrunde dauerhaft in der Offline-Queue parken, und der Gast bekaeme
 * nichts.
 */
private const val NOTE_MAX_LENGTH = 120

/**
 * Feste Liste in der Reihenfolge, in der die Wuensche an der Bude vorkommen.
 * Als Ressourcen-IDs, damit auch diese Chips kein Text im Code sind.
 */
private val NOTE_TEMPLATES = listOf(
    R.string.articles_note_ohne_senf,
    R.string.articles_note_extra_senf,
    R.string.articles_note_ohne_ketchup,
    R.string.articles_note_extra_ketchup,
    R.string.articles_note_ohne_zwiebel,
    R.string.articles_note_scharf,
)

/**
 * Notizdialog: freies Feld plus Vorlagen. Material-`AlertDialog` wie der Hinweis
 * im Tischraster — er ist einer der wenigen Material-Bausteine, die die App
 * bewusst behaelt.
 *
 * Einzeilig, weil `KassaTextField` einzeilig ist: der Server entfernt
 * Zeilenumbrueche ohnehin, und auf dem Bon steht die Notiz in einer Zeile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteDialog(
    articleName: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // `initial` als Schluessel: derselbe Dialog fuer einen anderen Artikel startet
    // damit nicht mit dem Text des vorherigen.
    var text by rememberSaveable(initial) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.articles_note_title, articleName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KassaTextField(
                    value = text,
                    onValueChange = { text = it.take(NOTE_MAX_LENGTH) },
                    placeholder = stringResource(R.string.articles_note_placeholder),
                    label = stringResource(R.string.articles_note),
                )

                // `FlowRow` statt Raster: der Dialog ist beliebig breit, und die
                // Chips brechen dann von selbst um.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (templateId in NOTE_TEMPLATES) {
                        val template = stringResource(templateId)
                        NoteTemplateChip(
                            label = template,
                            selected = noteHasTemplate(text, template),
                            onClick = { text = toggleNoteTemplate(text, template) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * Eigener Chip statt Material-`FilterChip`: der waere 32dp hoch und damit unter
 * der kleinsten antippbaren Kante.
 */
@Composable
private fun NoteTemplateChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = KassaTheme.colors
    val dimens = KassaTheme.dimens
    val shape = RoundedCornerShape(dimens.controlRadius)
    // Ausserhalb des `semantics`-Lambdas gebunden: dort verdeckt die
    // gleichnamige Semantik-Eigenschaft den Parameter.
    val isActive = selected

    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = dimens.minTouchTarget)
            .background(if (selected) colors.accent else colors.groupedBackground, shape)
            .then(
                if (selected) Modifier
                else Modifier.border(dimens.hairline, colors.separator, shape)
            )
            .semantics {
                role = Role.Button
                this.selected = isActive
            }
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = KassaTheme.typography.body,
            color = if (selected) colors.onAccent else colors.label,
            maxLines = 1,
        )
    }
}

/**
 * Ein Chip haengt sich kommagetrennt an, ein zweiter Tipp nimmt ihn wieder weg.
 * Frei getippter Text bleibt dabei stehen — Tippen und Antippen soll sich
 * mischen lassen.
 */
internal fun toggleNoteTemplate(note: String, template: String): String {
    val parts = noteParts(note)
    val next = if (parts.any { it.equals(template, ignoreCase = true) }) {
        parts.filterNot { it.equals(template, ignoreCase = true) }
    } else {
        parts + template
    }
    return next.joinToString(", ").take(NOTE_MAX_LENGTH)
}

internal fun noteHasTemplate(note: String, template: String): Boolean =
    noteParts(note).any { it.equals(template, ignoreCase = true) }

private fun noteParts(note: String): List<String> =
    note.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Tippbar ohne Material-Ripple — dieselbe Entscheidung wie im Tischraster: iOS
 * quittiert einen Druck nicht mit einer auslaufenden Welle.
 */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)

// MARK: - Vorschau

private fun previewArticles(): List<ArticleEntity> = listOf(
    ArticleEntity("s1", "Speisen", "Schweinsbraten mit Knödel", 1_290, 1, true),
    ArticleEntity("s2", "Speisen", "Liptauer mit Bauernbrot", 680, 2, true),
    ArticleEntity("s3", "Speisen", "Verhackertes", 590, 3, true),
    ArticleEntity("s4", "Speisen", "Blunzengröstl", 1_090, 4, true),
    ArticleEntity("g1", "Getraenke", "Grüner Veltliner 1/8", 320, 5, true),
    ArticleEntity("g2", "Getraenke", "Gespritzter 1/4", 380, 6, true),
    ArticleEntity("g3", "Getraenke", "Almdudler 0,35", 350, 7, true),
)

@PreviewLightDark
@Composable
private fun ArticlePickerPreview() {
    KassaTheme {
        ArticlePickerScreen(
            tableNumber = 3,
            articles = previewArticles(),
            onBack = {},
            onBook = {},
        )
    }
}

/**
 * Die Notizzeile laesst sich nicht ueber den Bildschirm vorschauen — der Entwurf
 * ist Bildschirmzustand und in einer Vorschau immer leer. Deshalb die Kachel
 * direkt, mit und ohne Notiz.
 */
@PreviewLightDark
@Composable
private fun ArticleTileNotePreview() {
    KassaTheme {
        Column(
            modifier = Modifier
                .background(KassaTheme.colors.groupedBackground)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ArticleTile(
                article = previewArticles()[0],
                count = 2,
                note = "ohne Senf, extra Ketchup",
                onIncrement = {},
                onDecrement = {},
                onEditNote = {},
            )
            ArticleTile(
                article = previewArticles()[1],
                count = 1,
                note = null,
                onIncrement = {},
                onDecrement = {},
                onEditNote = {},
            )
        }
    }
}

@Preview(name = "Notizdialog", showBackground = true)
@Composable
private fun NoteDialogPreview() {
    KassaTheme {
        NoteDialog(
            articleName = "Käsekrainer",
            initial = "ohne Senf",
            onDismiss = {},
            onConfirm = {},
        )
    }
}

@Preview(name = "Artikel ohne Katalog", showBackground = true)
@Composable
private fun ArticlePickerEmptyPreview() {
    KassaTheme {
        ArticlePickerScreen(
            tableNumber = 3,
            articles = emptyList(),
            onBack = {},
            onBook = {},
        )
    }
}

@Preview(name = "Artikel grosse Schrift", showBackground = true, fontScale = 1.5f)
@Composable
private fun ArticlePickerLargeFontPreview() {
    KassaTheme {
        ArticlePickerScreen(
            tableNumber = 3,
            articles = previewArticles(),
            onBack = {},
            onBook = {},
        )
    }
}
