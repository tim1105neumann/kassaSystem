package at.heuriger.kassa.data.db

import androidx.room.TypeConverter
import java.time.Instant
import java.util.UUID

/**
 * UUID als TEXT, Instant als Epoch-Sekunden.
 *
 * Sekunden reichen, weil jeder selbst erzeugte Zeitstempel ueber
 * `KassaClock.now()` laeuft und dort bereits auf Sekunden gekuerzt wird — dieselbe
 * Aufloesung, die auch der [at.heuriger.kassa.wire.InstantSerializer] ueber die
 * Leitung schickt. Damit ist ein lokal gehaltener Zeitstempel nach dem Rundlauf
 * ueber den Server bitgleich.
 */
object Converters {

    @TypeConverter
    fun uuidToString(value: UUID?): String? = value?.toString()

    @TypeConverter
    fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)

    @TypeConverter
    fun instantToEpochSeconds(value: Instant?): Long? = value?.epochSecond

    @TypeConverter
    fun epochSecondsToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochSecond)
}
