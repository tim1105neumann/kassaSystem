package at.heuriger.kassa.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Farbtokens in iOS-Anmutung.
 *
 * Der Kunde benutzt iPhone und Android nebeneinander, deshalb sind das die
 * Systemfarben von iOS und nicht die von Material. Die Werte stammen aus Apples
 * Human Interface Guidelines (System Colors, Grouped Background Levels) und sind
 * je einmal fuer hell und einmal fuer dunkel hinterlegt.
 *
 * **Eine Abweichung, und zwar eine notwendige.** Die fuenf Signalfarben sind
 * gegenueber Apples Originalen abgedunkelt. Sie sind hier keine Textfarben,
 * sondern *Flaechen*, auf denen weisse Schrift steht — Tischkachel und
 * Verbindungsbanner. Apples Werte verfehlen in dieser Rolle WCAG 2.1 AA
 * deutlich: Blau 4,02:1, Rot 3,55:1, Grau 3,26:1, Gruen 2,22:1, Orange 2,20:1.
 * Auf einem Telefon im Schanigarten bei Sonne ist das der Unterschied zwischen
 * lesbar und geraten.
 *
 * Abgedunkelt wird durch Skalieren aller drei Kanaele mit demselben Faktor. Das
 * senkt in HSV nur den Hellwert und laesst Farbton und Saettigung unberuehrt —
 * Gruen bleibt Gruen, Orange bleibt Orange. Der Faktor ist pro Farbe der
 * kleinste, der 4,65:1 gegen Weiss erreicht (ein wenig Reserve ueber den
 * geforderten 4,5:1). Er faellt sehr unterschiedlich aus: Blau braucht 9 %,
 * Gruen und Orange gut ein Drittel — die beiden sind von Haus aus die hellsten.
 *
 * Halbtransparente Label-Farben sind bewusst mit Alpha notiert statt
 * vorberechnet: sie liegen auf unterschiedlich hellen Flaechen (Kachel, Banner,
 * Gruppenhintergrund) und muessen sich dort jeweils richtig mischen. Weisse
 * Schrift *auf* den Signalfarben ist aus demselben Grund deckend und nicht
 * abgeschwaecht: schon 85 % Deckkraft druecken den Kontrast unter 4,5:1.
 */
@Immutable
data class KassaColors(
    /** Seitenhintergrund — iOS `systemGroupedBackground`. */
    val groupedBackground: Color,
    /** Kachel- und Zeilenflaeche — iOS `secondarySystemGroupedBackground`. */
    val surface: Color,
    /** Haupttext — iOS `label`. */
    val label: Color,
    /** Nebentext — iOS `secondaryLabel`. */
    val secondaryLabel: Color,
    /** Beiwerk wie Chevrons — iOS `tertiaryLabel`. */
    val tertiaryLabel: Color,
    /** Trennlinien und Kachelumrandung — iOS `separator`. */
    val separator: Color,
    /** Aktionsfarbe; zugleich die Flaeche eines offenen Tisches. */
    val accent: Color,
    /** Text auf [accent] und auf den Bannerfarben. */
    val onAccent: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val blue: Color,
    val gray: Color,
)

/**
 * Hell. `groupedBackground` ist absichtlich das graue `systemGroupedBackground`
 * und `surface` das weisse Feld darauf — nur durch diesen Kontrast liest sich
 * eine Kachel ueberhaupt als Kachel.
 */
val KassaLightColors = KassaColors(
    groupedBackground = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x5C3C3C43),
    // systemBlue #007AFF, 9 % abgedunkelt — 4,67:1 gegen Weiss.
    accent = Color(0xFF0070E9),
    onAccent = Color(0xFFFFFFFF),
    // systemGreen #34C759, 33 % abgedunkelt — 4,68:1.
    green = Color(0xFF23853C),
    // systemOrange #FF9500, 34 % abgedunkelt — 4,67:1.
    orange = Color(0xFFAA6300),
    // systemRed #FF3B30, 15 % abgedunkelt — 4,70:1.
    red = Color(0xFFDA3229),
    blue = Color(0xFF0070E9),
    // systemGray #8E8E93, 19 % abgedunkelt — 4,65:1.
    gray = Color(0xFF747478),
)

/**
 * Dunkel. Der Seitenhintergrund ist reines Schwarz und die Kachel das hellere
 * Feld — die Ebenen drehen sich gegenueber hell also um, genau wie auf iOS.
 *
 * Die Signalfarben gehen hier denselben Weg wie im hellen Schema und sind
 * dadurch *dunkler* als Apples Dark-Varianten, nicht heller. Das klingt
 * verkehrt, ist aber die Folge davon, wofuer sie hier stehen: die Kachel traegt
 * auch im dunklen Erscheinungsbild weisse Schrift, der Kontrast wird also gegen
 * Weiss gemessen und nicht gegen den schwarzen Seitenhintergrund. Gegen den
 * bleiben sie mit rund 4,5:1 weiterhin klar abgesetzt — eine Kachel ist als
 * Kachel zu erkennen.
 */
val KassaDarkColors = KassaColors(
    groupedBackground = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0x5C545458),
    // systemBlue (dark) #0A84FF, 14 % abgedunkelt — 4,71:1 gegen Weiss.
    accent = Color(0xFF0972DD),
    onAccent = Color(0xFFFFFFFF),
    // systemGreen (dark) #30D158, 37 % abgedunkelt — 4,70:1.
    green = Color(0xFF1E8538),
    // systemOrange (dark) #FF9F0A, 36 % abgedunkelt — 4,71:1.
    orange = Color(0xFFA36606),
    // systemRed (dark) #FF453A, 16 % abgedunkelt — 4,66:1.
    red = Color(0xFFD63A31),
    blue = Color(0xFF0972DD),
    // systemGray #8E8E93, 19 % abgedunkelt — 4,65:1.
    gray = Color(0xFF747478),
)
