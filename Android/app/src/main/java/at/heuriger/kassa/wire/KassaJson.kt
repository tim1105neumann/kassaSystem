package at.heuriger.kassa.wire

import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Beide Seiten muessen Daten identisch codieren, sonst verschieben sich
 * Zeitstempel. Deshalb liegt die Konfiguration hier und nicht doppelt.
 *
 * Die drei Flags sind keine Geschmacksfrage, sondern spiegeln Swifts Verhalten:
 * - `explicitNulls = false`: Swifts synthetisierter Encoder nutzt `encodeIfPresent`
 *   und laesst `nil`-Felder ganz weg, statt `null` zu schreiben.
 * - `encodeDefaults = true`: `tipCents` muss auch als 0 mitgehen.
 * - `ignoreUnknownKeys = true`: ein neuerer Server darf Felder ergaenzen,
 *   ohne aeltere Clients zu zerbrechen.
 */
object KassaJson {
    val instance: Json = Json {
        explicitNulls = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}

/** Route-Konstanten an einer Stelle, damit App und Server nicht auseinanderlaufen. */
object ApiRoute {
    const val LOGIN = "auth/login"
    const val CONFIG = "config"
    const val ARTICLES = "articles"
    const val SYNC = "sync"
    const val ORDER_LINES = "orders/lines"
    const val SETTLEMENTS = "settlements"
    const val PRINT_REQUESTS = "print-requests"
    const val DAY_REPORT = "reports/day"
    const val DEVICES = "devices"
    const val WEB_SOCKET = "ws"

    fun voidLine(id: UUID): String = "orders/lines/$id/void"
}
