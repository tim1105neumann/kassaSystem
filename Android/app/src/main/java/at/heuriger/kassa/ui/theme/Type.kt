package at.heuriger.kassa.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Schriftrollen in iOS-Anmutung.
 *
 * Die Groessen sind Apples Text Styles bei Standard-Dynamic-Type (largeTitle 34,
 * title2 22, body/headline 17, subheadline 15, caption 12). Alles in `sp`, damit
 * die Schriftgroesse aus den Systemeinstellungen durchschlaegt — `dp` waere hier
 * ein Barrierefreiheitsfehler.
 *
 * **Abweichung von der Vorlage.** iOS setzt Betraege in `.rounded` (SF Rounded).
 * Android hat keine garantierte abgerundete Systemschrift: die AOSP-Fonts
 * enthalten keine, und der einzige Kandidat waere die variable `RobotoFlex` mit
 * ihrer `ROND`-Achse — die liegt aber nur auf manchen Geraeten unter
 * `/system/fonts` und laedt in Compose erst verzoegert, ein Fehlgriff wuerde also
 * nicht beim Start, sondern irgendwann mitten in der Komposition auffallen. Statt
 * dieses Risikos traegt die Zahlenrolle die Systemschrift in schwerem Schnitt.
 *
 * Was dagegen uebernommen ist: `tnum` schaltet auf Tabellenziffern mit fester
 * Breite. Untereinanderstehende Betraege fluchten damit, und eine Summe
 * zappelt beim Hochzaehlen nicht mehr in der Breite.
 */
@Immutable
data class KassaTypography(
    /** Grosser Navigationstitel — iOS `largeTitle`. */
    val largeTitle: TextStyle,
    /** Abschnittsueberschrift in Versalien — iOS Form-Section-Header. */
    val sectionHeader: TextStyle,
    /** Hervorgehobene Zeile — iOS `headline`. */
    val headline: TextStyle,
    /** Fliesstext und Eingabefelder — iOS `body`. */
    val body: TextStyle,
    /** Nebenzeile — iOS `subheadline`. */
    val subheadline: TextStyle,
    /** Kleinste Zeile, etwa die Tischlaufzeit — iOS `caption`. */
    val caption: TextStyle,
    /** Tischnummer auf der Kachel — 34sp, schwer, Tabellenziffern. */
    val tileNumber: TextStyle,
    /** Betrag auf der Kachel — halbfett, Tabellenziffern. */
    val amount: TextStyle,
)

private val Tabular = "tnum"

val KassaDefaultTypography = KassaTypography(
    largeTitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
    ),
    sectionHeader = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    headline = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    subheadline = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    caption = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    tileNumber = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontFeatureSettings = Tabular,
    ),
    amount = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = Tabular,
    ),
)
