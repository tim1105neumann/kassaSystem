package at.heuriger.kassa.net

import at.heuriger.kassa.wire.SettlementConflictDto

/**
 * Spiegel von `APIError` aus `App/Sources/Sync/KassaAPI.swift`.
 *
 * Die Faelle sind bewusst deckungsgleich mit der iOS-App: beide Clients haengen
 * an derselben Offline-Queue-Logik, und die entscheidet allein ueber
 * [disposition].
 */
sealed class ApiException(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Server-URL oder Token fehlen — kein Netzfehler, sondern Einrichtung. */
    data object NotConfigured : ApiException("Server-URL oder Anmeldung fehlt.")

    data object Unauthorized : ApiException("Anmeldung abgelaufen.")

    class SettlementConflict(
        val conflict: SettlementConflictDto,
    ) : ApiException("Kassier-Konflikt: ${conflict.reason}")

    class Server(
        val status: Int,
        val reason: String?,
    ) : ApiException(reason?.let { "Server-Fehler $status: $it" } ?: "Server-Fehler $status.")

    class Transport(cause: Throwable) : ApiException("Keine Verbindung zum Server.", cause)

    class Decoding(cause: Throwable) : ApiException("Antwort des Servers nicht lesbar.", cause)

    /** Wie die Offline-Queue mit dem Fehler umgeht. */
    enum class Disposition {
        /** Spaeter erneut versuchen, Reihenfolge bleibt erhalten. */
        RETRY,

        /** Wird sich nicht heilen — Command als fehlgeschlagen ablegen. */
        PERMANENT,

        /** Anmeldung noetig; die ganze Queue pausiert. */
        NEEDS_LOGIN,
    }

    val disposition: Disposition
        get() = when (this) {
            is NotConfigured, is Transport -> Disposition.RETRY
            is Unauthorized -> Disposition.NEEDS_LOGIN
            is SettlementConflict, is Decoding -> Disposition.PERMANENT
            // 408/429 sind voruebergehend, alles andere im 4xx-Bereich nicht.
            is Server -> if (status == 408 || status == 429 || status >= 500) {
                Disposition.RETRY
            } else {
                Disposition.PERMANENT
            }
        }

    val isOffline: Boolean
        get() = this is Transport || this is NotConfigured
}
