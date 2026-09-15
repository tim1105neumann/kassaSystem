package at.heuriger.kassa.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Geldbetrag in ganzen Cent. Bewusst kein `Double` — Rundungsfehler haben in
 * einer Kassa nichts verloren.
 *
 * Ueber die Leitung geht eine nackte Zahl, nie ein Objekt und nie ein Float.
 */
@Serializable(with = MoneySerializer::class)
data class Money(val cents: Int) : Comparable<Money> {

    override fun compareTo(other: Money): Int = cents.compareTo(other.cents)

    operator fun plus(other: Money): Money = Money(cents + other.cents)

    operator fun minus(other: Money): Money = Money(cents - other.cents)

    operator fun times(factor: Int): Money = Money(cents * factor)

    /** Oesterreichische Schreibweise, z. B. `6,20 €`. */
    val formatted: String
        get() = "$formattedPlain €"

    /** Ohne Waehrungszeichen, z. B. `6,20` — fuer Eingabefelder. */
    val formattedPlain: String
        get() {
            val sign = if (cents < 0) "-" else ""
            val absolute = kotlin.math.abs(cents)
            val rest = absolute % 100
            return "$sign${absolute / 100},${if (rest < 10) "0$rest" else "$rest"}"
        }

    companion object {
        val ZERO = Money(0)

        /** Parst `6,20`, `6.20`, `6` oder `,50`. Gibt `null` bei Unsinn zurueck. */
        fun parse(text: String): Money? {
            val trimmed = text.trim().replace("€", "").trim().replace(",", ".")
            if (trimmed.isEmpty()) return null

            val negative = trimmed.startsWith("-")
            val body = if (negative) trimmed.drop(1) else trimmed
            val parts = body.split(".")
            if (parts.size > 2) return null

            val euroPart = parts[0].ifEmpty { "0" }
            if (!euroPart.all { it in '0'..'9' }) return null
            // Long, nicht Int: Swifts Int ist 64 Bit, Kotlins 32. Ohne das liefern
            // schon acht Ziffern einen still umgeschlagenen, teils negativen Betrag —
            // in einer Kassa der schlechtestmoegliche Ausgang.
            val euro = euroPart.toLongOrNull() ?: return null

            var centPart = 0L
            if (parts.size == 2) {
                val raw = parts[1]
                if (raw.length > 2 || !raw.all { it in '0'..'9' }) return null
                centPart = raw.padEnd(2, '0').toLongOrNull() ?: return null
            }

            val total = euro * 100 + centPart
            if (total > Int.MAX_VALUE) return null
            return Money((if (negative) -total else total).toInt())
        }
    }
}

/** Money liegt als reine Cent-Zahl auf der Leitung — exakt wie Swifts `singleValueContainer`. */
object MoneySerializer : KSerializer<Money> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("at.heuriger.kassa.wire.Money", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Money) = encoder.encodeInt(value.cents)

    override fun deserialize(decoder: Decoder): Money = Money(decoder.decodeInt())
}
