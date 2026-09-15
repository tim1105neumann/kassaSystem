package at.heuriger.kassa.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Abstaende und Radien, uebernommen aus `TableGridView.swift` und
 * `ConnectionBanner.swift` — dort stehen dieselben Zahlen als Literale.
 *
 * `dp` statt `sp` ist hier richtig: das sind Flaechenmasse, keine Schriftmasse.
 * Die Kachelhoehe ist deshalb auch nur ein *Mindest*mass — waechst die Schrift
 * ueber die Systemeinstellung, waechst die Kachel mit, statt den Text zu
 * beschneiden.
 */
@Immutable
data class KassaDimens(
    /** Seitenrand des Inhalts. */
    val screenPadding: Dp = 16.dp,
    /** Abstand zwischen Kacheln, waagrecht wie senkrecht. */
    val gridSpacing: Dp = 12.dp,
    /** Kleinste Kachelbreite; daraus errechnet das Raster seine Spaltenzahl. */
    val tileMinWidth: Dp = 104.dp,
    val tileMinHeight: Dp = 96.dp,
    val tilePadding: Dp = 12.dp,
    /** Eckenradius von Kachel und Banner. */
    val cornerRadius: Dp = 16.dp,
    /** Eckenradius von Eingabefeldern und Knoepfen. */
    val controlRadius: Dp = 12.dp,
    val hairline: Dp = 1.dp,
    /**
     * Kleinste antippbare Kante. Android verlangt 48dp — mehr als iOS' 44pt,
     * also gilt hier der groessere Wert.
     */
    val minTouchTarget: Dp = 48.dp,
    /** Hoehe des Anmeldeknopfs, wie `minHeight: 52` in `LoginView.swift`. */
    val buttonHeight: Dp = 52.dp,
)

val KassaDefaultDimens = KassaDimens()
