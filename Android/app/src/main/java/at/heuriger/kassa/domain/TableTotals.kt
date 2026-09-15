package at.heuriger.kassa.domain

import at.heuriger.kassa.wire.Money
import java.time.Instant

/** Spiegelt `TableTotals` aus `App/Sources/Model/Calculations.swift`. */
object TableTotals {

    /**
     * Nur offene Zeilen zaehlen: Storniertes und bereits Kassiertes gehoert
     * nicht mehr zur Tischsumme.
     */
    fun openTotal(lines: List<OrderLine>): Money =
        lines.fold(Money.ZERO) { sum, line -> if (line.isOpen) sum + line.lineTotal else sum }

    fun openLines(lines: List<OrderLine>): List<OrderLine> =
        lines.filter { it.isOpen }.sortedBy { it.createdAt }

    /** Aeltester offener Zeitstempel eines Tisches — daraus wird „seit 42 min". */
    fun openedAt(lines: List<OrderLine>): Instant? =
        lines.filter { it.isOpen }.minOfOrNull { it.createdAt }
}
