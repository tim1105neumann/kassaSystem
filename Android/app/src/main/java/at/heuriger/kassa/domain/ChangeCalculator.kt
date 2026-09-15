package at.heuriger.kassa.domain

import at.heuriger.kassa.wire.Money

/**
 * Ergebnis einer Rueckgeld-Rechnung. Zu wenig Geld ist ein eigener Fall, kein
 * negativer Betrag — sonst liest die Kellnerin im Stress ein Minus als Rueckgeld.
 */
sealed interface ChangeResult {
    data class Success(val change: Money) : ChangeResult
    data class NotEnough(val missing: Money) : ChangeResult
}

/** Spiegelt `ChangeCalculator` aus `App/Sources/Model/Calculations.swift`. */
object ChangeCalculator {

    fun change(total: Money, given: Money): ChangeResult =
        if (given < total) ChangeResult.NotEnough(missing = total - given)
        else ChangeResult.Success(change = given - total)

    /**
     * Schnellwahl: passend, sinnvoll aufgerundete Betraege und die gaengigen
     * Scheine, die den Betrag decken.
     *
     * Alles mit Ganzzahl-Arithmetik — in einer Kassa hat `Double` nichts verloren.
     */
    fun quickAmounts(total: Money): List<Money> {
        if (total.cents <= 0) return emptyList()

        val candidates = sortedSetOf(total.cents)
        for (step in intArrayOf(50, 100, 500, 1_000)) {
            candidates.add(roundedUp(total.cents, step))
        }
        for (note in intArrayOf(1_000, 2_000, 5_000, 10_000)) {
            if (note >= total.cents) candidates.add(note)
        }

        return candidates.take(6).map { Money(it) }
    }
}

/** Naechstes Vielfaches von [step] — rein ganzzahlig, ohne Rundungsfehler. */
internal fun roundedUp(cents: Int, step: Int): Int = (cents + step - 1) / step * step
