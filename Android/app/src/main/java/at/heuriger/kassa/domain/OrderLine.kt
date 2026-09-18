package at.heuriger.kassa.domain

import at.heuriger.kassa.wire.Money
import java.time.Instant
import java.util.UUID

/**
 * Leichtgewichtige Bestellzeile fuer die Rechen-Domaene — das Pendant zu
 * `LocalOrderLine` aus der iOS-App.
 *
 * Bewusst ohne Room-Annotationen: die Entities kommen spaeter und werden auf
 * diesen Typ abgebildet, damit die Berechnungen frei von Framework-Abhaengigkeiten
 * bleiben.
 */
data class OrderLine(
    val id: UUID,
    val tableNumber: Int,
    val articleId: String,
    /**
     * Name und Preis werden bei der Buchung eingefroren: eine spaetere
     * Preisaenderung darf alte Rechnungen nicht rueckwirkend veraendern.
     */
    val nameSnapshot: String,
    val unitPriceCents: Int,
    val qty: Int,
    val createdAt: Instant,
    val deviceId: String,
    val voidedAt: Instant? = null,
    val settlementId: UUID? = null,
    val updatedSeq: Int = 0,
    /** Sonderwunsch zu dieser Position, z. B. „ohne Senf". */
    val note: String? = null,
    /** Noch nicht vom Server bestaetigt — liegt in der Offline-Queue. */
    val pendingLocal: Boolean = false,
    /**
     * Platz in der Tischliste, in Epoch-Millisekunden. Rein lokal: der Vertrag
     * kennt das Feld nicht, Zeilen von einem anderen Geraet haben deshalb `null`.
     *
     * Noetig, weil eine Mengenreduktion die Zeile storniert und den Rest neu
     * bucht — die Ersatzzeile erbt den Schluessel und bleibt damit an ihrem
     * Platz, waehrend ihr `createdAt` ehrlich der Zeitpunkt der Neubuchung
     * bleibt. Den braucht der Kuechendrucker, um Nachzuegler zu erkennen
     * (`PrintService/Sources/KuechenbonCore/BonPlanner.swift`).
     */
    val sortKey: Long? = null,
) {
    val lineTotal: Money get() = Money(unitPriceCents * qty)

    /** Sortierwert der Anzeige: eigener Schluessel, sonst der Buchungszeitpunkt. */
    val orderKey: Long get() = sortKey ?: createdAt.toEpochMilli()

    /** Offen = weder storniert noch kassiert. */
    val isOpen: Boolean get() = voidedAt == null && settlementId == null
}
