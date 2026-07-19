package com.andreiefimov.torrentwebui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentViewModelMappingTest {

    private fun diagnostics(sessionStarted: Boolean, lastError: String? = null) = NativeDiagnostics(
        abi = "test",
        libtorrentVersion = "test",
        nativeLoaded = true,
        sessionStarted = sessionStarted,
        lastError = lastError
    )

    @Test
    fun `starting lifecycle does not imply a started native session`() {
        val mapped = mapDaemonUiHealth(
            diagnostics(sessionStarted = false),
            TorrentDaemon.DaemonHealthStatus("Starting", recoveryBlocked = false, lastRecoverableError = null)
        )

        assertFalse(mapped.sessionStarted)
    }

    @Test
    fun `recovery blocked lifecycle does not imply a started native session`() {
        val mapped = mapDaemonUiHealth(
            diagnostics(sessionStarted = false),
            TorrentDaemon.DaemonHealthStatus("RecoveryBlocked", recoveryBlocked = true, lastRecoverableError = null)
        )

        assertFalse(mapped.sessionStarted)
    }

    @Test
    fun `running lifecycle displays the real started session`() {
        val mapped = mapDaemonUiHealth(
            diagnostics(sessionStarted = true),
            TorrentDaemon.DaemonHealthStatus("Running", recoveryBlocked = false, lastRecoverableError = null)
        )

        assertTrue(mapped.sessionStarted)
    }

    @Test
    fun `recoverable daemon startup error fills empty session diagnostics`() {
        val mapped = mapDaemonUiHealth(
            diagnostics(sessionStarted = false),
            TorrentDaemon.DaemonHealthStatus(
                "Stopped",
                recoveryBlocked = false,
                lastRecoverableError = "Torrent engine failed to initialize"
            )
        )

        assertEquals("Torrent engine failed to initialize", mapped.diagnostics.lastError)
    }

    @Test
    fun `session diagnostics remain preferred over daemon fallback error`() {
        val mapped = mapDaemonUiHealth(
            diagnostics(sessionStarted = false, lastError = "Native detail"),
            TorrentDaemon.DaemonHealthStatus("Stopped", false, "Daemon detail")
        )

        assertEquals("Native detail", mapped.diagnostics.lastError)
    }
}
