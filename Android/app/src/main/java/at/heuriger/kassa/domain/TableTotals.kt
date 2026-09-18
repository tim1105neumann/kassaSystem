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

    /**
     * Die Reihenfolge ist vollstaendig bestimmt: nach [OrderLine.orderKey] und
     * bei Gleichstand nach [OrderLine.id]. Ein blosses `sortedBy { createdAt }`
     * reichte nicht — `createdAt` liegt auf Sekunden (`KassaClock`) und eine
     * Buchung aus dem Warenkorb legt alle Zeilen in derselben Sekunde an. Die
     * Reihenfolge haette dann die Datenbank bestimmt, und die hat sie nach
     * jedem Schreibvorgang neu gewuerfelt.
     */
    fun openLines(lines: List<OrderLine>): List<OrderLine> =
        lines.filter { it.isOpen }.sortedWith(compareBy({ it.orderKey }, { it.id }))

    /** Aeltester offener Zeitstempel eines Tisches — daraus wird „seit 42 min". */
    fun openedAt(lines: List<OrderLine>): Instant? =
        lines.filter { it.isOpen }.minOfOrNull { it.createdAt }
}
