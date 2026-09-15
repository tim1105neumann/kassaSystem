package at.heuriger.kassa.data

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/** Spiegel von `enum CommandKind` aus `App/Sources/Model/LocalModels.swift`. */
enum class CommandKind(val rawValue: String) {
    ADD_LINES("addLines"),
    VOID_LINE("voidLine"),
    SETTLE("settle");

    companion object {
        fun fromRawValue(rawValue: String): CommandKind? =
            entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * Kein DTO im Wire-Paket fuers Stornieren — die Route traegt die ID im Pfad.
 * Fuer die Queue brauchen wir sie aber als Payload.
 */
@Serializable
data class VoidLineCommand(
    @Serializable(with = at.heuriger.kassa.wire.UuidSerializer::class)
    val lineId: UUID,
)

/**
 * Kopie fuer die SyncEngine, damit die nicht auf Room-Entities zugreifen muss.
 * Spiegel von `PendingCommandSnapshot`.
 */
data class PendingCommandSnapshot(
    val id: UUID,
    val kind: CommandKind,
    val payload: String,
    val createdAt: Instant,
    val attemptCount: Int,
    val lastError: String? = null,
    val failedPermanently: Boolean = false,
)
