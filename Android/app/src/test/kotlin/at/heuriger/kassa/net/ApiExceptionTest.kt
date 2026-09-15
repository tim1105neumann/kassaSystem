package at.heuriger.kassa.net

import at.heuriger.kassa.wire.SettlementConflictDto
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spiegelt die `disposition`-Tabelle aus `App/Sources/Sync/KassaAPI.swift`.
 * Diese Zuordnung steuert die Offline-Queue: eine Verwechslung von RETRY und
 * PERMANENT bedeutet entweder eine ewig wiederholte Buchung oder eine still
 * verworfene Bestellung.
 */
class ApiExceptionTest {

    private val conflict = SettlementConflictDto(
        conflictingLineIds = emptyList(),
        settledByDevice = null,
        reason = SettlementConflictDto.Reason.ALREADY_SETTLED,
    )

    @Test
    fun `Transport und NotConfigured werden wiederholt`() {
        assertEquals(ApiException.Disposition.RETRY, ApiException.NotConfigured.disposition)
        assertEquals(
            ApiException.Disposition.RETRY,
            ApiException.Transport(IOException("kein Netz")).disposition,
        )
    }

    @Test
    fun `Unauthorized pausiert die Queue`() {
        assertEquals(ApiException.Disposition.NEEDS_LOGIN, ApiException.Unauthorized.disposition)
    }

    @Test
    fun `Konflikt und Decoding heilen nicht`() {
        assertEquals(
            ApiException.Disposition.PERMANENT,
            ApiException.SettlementConflict(conflict).disposition,
        )
        assertEquals(
            ApiException.Disposition.PERMANENT,
            ApiException.Decoding(IllegalStateException("kaputt")).disposition,
        )
    }

    @Test
    fun `nur 408, 429 und 5xx werden wiederholt`() {
        val retryable = listOf(408, 429, 500, 502, 503, 599)
        for (status in retryable) {
            assertEquals(
                "Status $status sollte wiederholt werden",
                ApiException.Disposition.RETRY,
                ApiException.Server(status, null).disposition,
            )
        }

        val permanent = listOf(400, 401, 403, 404, 409, 418, 422, 428, 430, 499)
        for (status in permanent) {
            assertEquals(
                "Status $status sollte endgueltig sein",
                ApiException.Disposition.PERMANENT,
                ApiException.Server(status, null).disposition,
            )
        }
    }

    @Test
    fun `isOffline gilt nur fuer Transport und NotConfigured`() {
        assertTrue(ApiException.Transport(IOException()).isOffline)
        assertTrue(ApiException.NotConfigured.isOffline)
        assertFalse(ApiException.Unauthorized.isOffline)
        assertFalse(ApiException.Server(500, null).isOffline)
        assertFalse(ApiException.SettlementConflict(conflict).isOffline)
    }

    @Test
    fun `Server-Fehlermeldung nennt Status und Grund`() {
        assertEquals("Server-Fehler 400: Unbekannter Artikel", ApiException.Server(400, "Unbekannter Artikel").message)
        assertEquals("Server-Fehler 500.", ApiException.Server(500, null).message)
    }
}
