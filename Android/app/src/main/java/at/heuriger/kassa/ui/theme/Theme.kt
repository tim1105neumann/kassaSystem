package at.heuriger.kassa.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Das Design-System der App.
 *
 * Der Kunde hat ausdruecklich iOS-Anmutung statt Material-Look gewaehlt: dieselben
 * Kellner bedienen iPhone und Android nebeneinander und sollen nicht umlernen.
 * Die eigenen Bildschirme lesen ihre Werte deshalb ueber [KassaTheme] und nie
 * ueber `MaterialTheme`.
 *
 * `MaterialTheme` wird trotzdem gesetzt, aber nicht *durchgereicht*: sein Schema
 * ist aus denselben Tokens abgeleitet. Grund ist eine Handvoll unvermeidbarer
 * Material-Bausteine (`PullToRefreshBox`, `AlertDialog`), die sonst mit Purple
 * aus den Material-Defaults oder mit Dynamic Color aus dem Systemhintergrund
 * erscheinen wuerden — beides mitten in einer ansonsten iOS-farbigen Oberflaeche.
 *
 * Dynamic Color ist aus demselben Grund bewusst abgeschaltet: ein Nutzer, der
 * sein Android-Wallpaper wechselt, darf die Kassa nicht umfaerben.
 */
object KassaTheme {
    val colors: KassaColors
        @Composable @ReadOnlyComposable get() = LocalKassaColors.current

    val typography: KassaTypography
        @Composable @ReadOnlyComposable get() = LocalKassaTypography.current

    val dimens: KassaDimens
        @Composable @ReadOnlyComposable get() = LocalKassaDimens.current
}

/**
 * `staticCompositionLocalOf`, weil sich diese drei Werte waehrend einer Sitzung
 * praktisch nie aendern. Der dynamische Gegenpart wuerde jede lesende Stelle in
 * einen Abhaengigkeitsgraphen eintragen, den niemand je benutzt.
 */
val LocalKassaColors = staticCompositionLocalOf { KassaLightColors }
val LocalKassaTypography = staticCompositionLocalOf { KassaDefaultTypography }
val LocalKassaDimens = staticCompositionLocalOf { KassaDefaultDimens }

@Composable
fun KassaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) KassaDarkColors else KassaLightColors

    CompositionLocalProvider(
        LocalKassaColors provides colors,
        LocalKassaTypography provides KassaDefaultTypography,
        LocalKassaDimens provides KassaDefaultDimens,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(darkTheme),
            content = content,
        )
    }
}

/**
 * Bildet die iOS-Tokens auf die Material-Rollen ab, die von den wenigen
 * verbliebenen Material-Bausteinen tatsaechlich gelesen werden. Alles andere
 * bleibt beim Material-Standard — es wird nie sichtbar.
 */
private fun KassaColors.toMaterialScheme(darkTheme: Boolean) =
    (if (darkTheme) darkColorScheme() else lightColorScheme()).copy(
        primary = accent,
        onPrimary = onAccent,
        background = groupedBackground,
        onBackground = label,
        surface = surface,
        onSurface = label,
        surfaceVariant = surface,
        onSurfaceVariant = secondaryLabel,
        surfaceContainerHigh = surface,
        error = red,
        outline = separator,
    )
