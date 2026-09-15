package at.heuriger.kassa.wire

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Einzige Quelle fuer selbst erzeugte Zeitstempel.
 *
 * Der [InstantSerializer] schreibt auf Sekunden gekuerzt und liest ebenso — ein
 * `Instant.now()` mit Nanosekunden waere nach dem Rundlauf also ein *anderer*
 * Wert als der lokal gehaltene. Genau daran wuerde spaeter jeder Merge-Vergleich
 * zwischen lokalem Stand und Serverantwort scheitern, und zwar sporadisch.
 *
 * Deshalb liegt die Kuerzung hier und nicht in jedem Aufrufer: wer einen
 * Zeitstempel fuer ein DTO braucht, nimmt [now] und nie `Instant.now()`.
 */
object KassaClock {
    fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
}
