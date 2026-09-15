package at.heuriger.kassa.data

import java.time.Instant

/** Spiegel von `enum ConnectionState` aus `App/Sources/Sync/LocalStore.swift`. */
enum class ConnectionState { CONNECTED, SYNCING, OFFLINE, NEEDS_LOGIN }

/**
 * Der beobachtbare Zustand des Stores — bewusst **ein** Objekt und nicht vier
 * einzelne Flows.
 *
 * Bei getrennten Flows sieht die UI zwangslaeufig Zwischenstaende: erst
 * `connection = CONNECTED`, im naechsten Frame erst `lastSyncAt`. Als ein
 * Datensatz wechselt der Zustand in einem Schritt.
 */
data class SyncStatus(
    val connection: ConnectionState = ConnectionState.OFFLINE,
    val lastSyncAt: Instant? = null,
    val lastErrorMessage: String? = null,
    /** Gesetzt, wenn ein Kassiervorgang serverseitig abgelehnt wurde. */
    val conflictMessage: String? = null,
)
