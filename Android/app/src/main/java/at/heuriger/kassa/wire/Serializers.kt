package at.heuriger.kassa.wire

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Zeitstempel exakt so, wie Swifts `JSONEncoder.dateEncodingStrategy = .iso8601`
 * sie schreibt: UTC, ISO-8601, **ohne Sekundenbruchteile**.
 *
 * Javas `Instant.toString()` haengt Millisekunden an. Der Server nimmt die zwar an,
 * wirft sie aber weg und antwortet mit der auf Sekunden reduzierten Form — der lokal
 * gespeicherte Zeitstempel wiche dann um bis zu 999 ms vom zurueckgelieferten ab und
 * jeder Merge bzw. Gleichheitsvergleich wuerde launisch. Deshalb wird beim Schreiben
 * auf Sekunden abgeschnitten.
 *
 * Beim Lesen ist der Serializer tolerant: Bruchteile und beliebige Zonen-Offsets
 * werden akzeptiert und ebenfalls auf Sekunden reduziert.
 */
object InstantSerializer : KSerializer<Instant> {

    private val WRITE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(WRITE_FORMAT.format(value.truncatedTo(ChronoUnit.SECONDS)))
    }

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        val parsed = try {
            OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: java.time.format.DateTimeParseException) {
            Instant.parse(raw)
        }
        return parsed.truncatedTo(ChronoUnit.SECONDS)
    }
}

/**
 * UUIDs gehen als String ueber die Leitung. Java schreibt klein, Swift gross —
 * am laufenden Server gemessen: kleingeschrieben ist einwandfrei, er normalisiert
 * selbst. Gelesen wird case-insensitiv, weil der Server gross antwortet.
 */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.util.UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}
