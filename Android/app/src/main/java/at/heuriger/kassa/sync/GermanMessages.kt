package at.heuriger.kassa.sync

import at.heuriger.kassa.net.ApiException
import at.heuriger.kassa.wire.SettlementConflictDto

/**
 * Spiegel von `APIError.germanMessage` und `SettlementConflictDTO.germanMessage`
 * aus `App/Sources/Sync/KassaAPI.swift`.
 *
 * Liegt hier und nicht in `at.heuriger.kassa.net`, weil [ApiException.message]
 * dort bewusst eine kurze Entwicklerbeschreibung ist. Was der Kellnerin
 * angezeigt wird, ist etwas anderes und gehoert zur Sync-Schicht, die diese
 * Texte auch in `lastError` schreibt.
 *
 * Die Texte stehen als Literale und nicht in `strings.xml`: sie entstehen in
 * einer Hintergrund-Coroutine ohne `Context` und landen in der Datenbank, nicht
 * direkt am Bildschirm. Die App ist ohnehin auf `de` festgelegt
 * (`localeFilters`). Sobald eine zweite Sprache dazukommt, muss daraus ein
 * Fehler-*Typ* werden, den die UI uebersetzt — nicht ein Text, den man
 * nachtraeglich lokalisiert.
 */
val ApiException.germanMessage: String
    get() = when (this) {
        is ApiException.NotConfigured ->
            "Server-URL oder Anmeldung fehlt. Bitte in den Einstellungen prüfen."
        is ApiException.Unauthorized ->
            "Anmeldung abgelaufen. Bitte neu anmelden."
        is ApiException.SettlementConflict -> conflict.germanMessage
        is ApiException.Server ->
            reason?.let { "Server-Fehler $status: $it" } ?: "Server-Fehler $status."
        is ApiException.Transport -> "Keine Verbindung zum Server."
        is ApiException.Decoding -> "Antwort des Servers nicht lesbar."
    }

val SettlementConflictDto.germanMessage: String
    get() = when (reason) {
        SettlementConflictDto.Reason.ALREADY_SETTLED ->
            settledByDevice?.let { "Wurde bereits von $it kassiert." } ?: "Wurde bereits kassiert."
        SettlementConflictDto.Reason.LINE_VOIDED ->
            "Eine der Positionen wurde zwischenzeitlich storniert."
        SettlementConflictDto.Reason.AMOUNT_MISMATCH ->
            "Der Betrag stimmt nicht mehr mit dem Serverstand überein."
        SettlementConflictDto.Reason.UNKNOWN_LINE ->
            "Eine der Positionen ist am Server nicht bekannt."
        SettlementConflictDto.Reason.QUANTITY_EXCEEDED ->
            "Es wurden mehr Stück ausgewählt, als noch offen sind."
    }

/**
 * Dieselbe Meldung, aber mit Tischnummer — beim Kassieren steht der Kellner vor
 * einem konkreten Tisch und „Wurde bereits kassiert" allein sagt ihm nicht, vor
 * welchem.
 */
fun SettlementConflictDto.germanMessage(tableNumber: Int): String =
    when (reason) {
        SettlementConflictDto.Reason.ALREADY_SETTLED -> settledByDevice
            ?.let { "Tisch $tableNumber wurde bereits von $it kassiert." }
            ?: "Tisch $tableNumber wurde bereits kassiert."
        else -> germanMessage
    }
