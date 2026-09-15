package at.heuriger.kassa.domain

import at.heuriger.kassa.wire.Money

/**
 * Ergebnis einer Trinkgeld-Rechnung. Weniger als die Rechnung ist ein eigener
 * Fall, kein negatives Trinkgeld.
 */
sealed interface TipResult {
    data class Success(val tip: Money) : TipResult
    data class BelowTotal(val missing: Money) : TipResult
}

/** Spiegelt `TipCalculator` aus `App/Sources/Model/Calculations.swift`. */
object TipCalculator {

    /** Trinkgeld aus dem Betrag, den der Gast nennt. */
    fun tip(total: Money, paid: Money): TipResult =
        if (paid < total) TipResult.BelowTotal(missing = total - paid)
        else TipResult.Success(tip = paid - total)

    /**
     * Aufrundungsvorschlaege: passend, naechste 50 Cent, naechster Euro,
     * +1 €, +2 € und der naechste Fuenfer.
     */
    fun quickTotals(total: Money): List<Money> {
        if (total.cents <= 0) return emptyList()

        val nextEuro = roundedUp(total.cents, 100)
        val candidates = sortedSetOf(total.cents, roundedUp(total.cents, 50), nextEuro)

        candidates.add(nextEuro + 100)
        candidates.add(nextEuro + 200)
        // Der Fuenfer muss ueber dem vollen Euro liegen — sonst waere er bei 29,70
        // wieder 30,00 und der Vorschlag doppelt.
        candidates.add((nextEuro / 500 + 1) * 500)

        return candidates.take(5).map { Money(it) }
    }
}
