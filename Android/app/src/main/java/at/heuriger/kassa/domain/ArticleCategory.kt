package at.heuriger.kassa.domain

/**
 * Die CSV schreibt `Getraenke` ohne Umlaut — die UI zeigt trotzdem „Getraenke"
 * mit Umlaut. Der Rohwert bleibt deshalb bewusst ASCII.
 *
 * Spiegelt `App/Sources/Model/Calculations.swift`.
 */
enum class ArticleCategory(val rawValue: String) {
    SPEISEN("Speisen"),
    GETRAENKE("Getraenke");

    val id: String get() = rawValue

    companion object {
        fun fromRawValue(rawValue: String): ArticleCategory? =
            entries.firstOrNull { it.rawValue == rawValue }
    }
}
