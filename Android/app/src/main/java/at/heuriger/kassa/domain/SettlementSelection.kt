package at.heuriger.kassa.domain

import android.os.Parcelable
import at.heuriger.kassa.wire.Money
import at.heuriger.kassa.wire.SettlementLineSelection
import java.util.UUID
import kotlinx.parcelize.Parcelize

/**
 * Auswahl `Zeilen-ID -> Menge` fuer eine Teilzahlung.
 *
 * Spiegelt Swifts `SettlementSelection`. Dort ist es ein `struct` mit einer
 * `mutating func`; hier ist der Typ unveraenderlich und [set] liefert eine neue
 * Auswahl zurueck — das ist dieselbe Wertsemantik und genau das, was Compose-State
 * braucht.
 *
 * [Parcelize] macht den Typ `rememberSaveable`-faehig.
 */
@Parcelize
class SettlementSelection private constructor(
    /** Nur Mengen > 0 — eine Null-Auswahl ist keine Auswahl. */
    val quantities: Map<UUID, Int>,
) : Parcelable {

    fun qty(lineId: UUID): Int = quantities[lineId] ?: 0

    val isEmpty: Boolean get() = quantities.isEmpty()

    /** Clamping auf `0..max`; bei 0 faellt der Schluessel raus. */
    fun set(qty: Int, lineId: UUID, max: Int): SettlementSelection {
        val clamped = qty.coerceIn(0, maxOf(max, 0))
        return if (clamped == 0) {
            normalized(quantities - lineId)
        } else {
            normalized(quantities + (lineId to clamped))
        }
    }

    /**
     * Zwischensumme der ausgewaehlten Mengen. Zeilen, die nicht mehr offen
     * sind, zaehlen nicht mit.
     */
    fun subtotal(lines: List<OrderLine>): Money =
        lines.fold(Money.ZERO) { sum, line ->
            val qty = quantities[line.id]
            if (!line.isOpen || qty == null) sum
            else sum + Money(line.unitPriceCents * minOf(qty, line.qty))
        }

    fun requestLines(lines: List<OrderLine>): List<SettlementLineSelection> =
        lines.mapNotNull { line ->
            val qty = quantities[line.id]
            if (!line.isOpen || qty == null || qty <= 0) null
            else SettlementLineSelection(lineId = line.id, qty = minOf(qty, line.qty))
        }

    override fun equals(other: Any?): Boolean =
        this === other || (other is SettlementSelection && quantities == other.quantities)

    override fun hashCode(): Int = quantities.hashCode()

    override fun toString(): String = "SettlementSelection($quantities)"

    companion object {
        private fun normalized(quantities: Map<UUID, Int>): SettlementSelection =
            SettlementSelection(quantities.filterValues { it > 0 })

        operator fun invoke(quantities: Map<UUID, Int> = emptyMap()): SettlementSelection =
            normalized(quantities)

        fun all(lines: List<OrderLine>): SettlementSelection =
            normalized(lines.associate { it.id to it.qty })
    }
}
