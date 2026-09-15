package at.heuriger.kassa.ui

import kotlinx.serialization.Serializable

/**
 * Die Ziele der Navigation. Spiegel von `enum Route` aus `RootView.swift`.
 *
 * `@Serializable` statt String-Pfaden: Navigation Compose loest die Ziele damit
 * typsicher auf. Ein `Route.Table(7)` traegt seine Tischnummer als `Int` durch
 * den Stapel, statt sie in einen Pfad zu schreiben und auf der anderen Seite
 * wieder herauszuparsen — der Fehlerfall „Tischnummer war kein Int" entsteht
 * dabei gar nicht erst.
 *
 * [Table], [Articles] und [Settle] stehen hier bereits, weil sie die
 * Tischnummer identisch tragen und die Vorlage sie so kennt. Sie haben in
 * dieser Runde noch kein Ziel im [KassaNavHost] ausser dem Platzhalter fuer
 * [Table].
 */
@Serializable
sealed class Route {

    /**
     * Der Startbildschirm. Zeigt Raster **oder** Anmeldung — wie auf iOS, wo
     * beide im selben `NavigationStack`-Wurzelelement liegen. Nach dem Anmelden
     * wechselt der Inhalt, ohne dass ein Bildschirm geschoben wird.
     */
    @Serializable
    data object Home : Route()

    @Serializable
    data object DayReport : Route()

    @Serializable
    data object Settings : Route()

    @Serializable
    data class Table(val number: Int) : Route()

    @Serializable
    data class Articles(val number: Int) : Route()

    @Serializable
    data class Settle(val number: Int) : Route()
}
