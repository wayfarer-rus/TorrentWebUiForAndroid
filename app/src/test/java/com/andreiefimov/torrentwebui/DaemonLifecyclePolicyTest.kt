package com.andreiefimov.torrentwebui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonLifecyclePolicyTest {

    @Test
    fun `start during stopping is coalesced and dispatched after successful stop`() {
        val coalescer = DaemonStartCoalescer()

        assertEquals(
            StartCommandDecision.Queued,
            coalescer.onStart(TorrentDaemon.DaemonState.Stopping, userInitiated = false)
        )
        assertEquals(
            StartCommandDecision.Queued,
            coalescer.onStart(TorrentDaemon.DaemonState.Stopping, userInitiated = true)
        )

        assertTrue(coalescer.takePendingAfterStop(stopSucceeded = true) == true)
        assertNull(coalescer.takePendingAfterStop(stopSucceeded = true))
    }

    @Test
    fun `failed safe stop discards pending start because daemon remains running`() {
        val coalescer = DaemonStartCoalescer()
        coalescer.onStart(TorrentDaemon.DaemonState.Stopping, userInitiated = true)

        assertNull(coalescer.takePendingAfterStop(stopSucceeded = false))
        assertEquals(
            StartCommandDecision.Ignored,
            coalescer.onStart(TorrentDaemon.DaemonState.Running, userInitiated = false)
        )
    }

    @Test
    fun `native destroy failure discards queued restart`() {
        val coalescer = DaemonStartCoalescer()
        coalescer.onStart(TorrentDaemon.DaemonState.Stopping, userInitiated = true)

        assertNull(coalescer.takePendingAfterStop(stopSucceeded = false))
    }

    @Test
    fun `null service restart is non sticky`() {
        assertEquals(android.app.Service.START_NOT_STICKY, serviceRestartMode(hasIntent = false))
        assertEquals(android.app.Service.START_STICKY, serviceRestartMode(hasIntent = true))
    }

    @Test
    fun `stopped starts immediately while live states ignore duplicate starts`() {
        val coalescer = DaemonStartCoalescer()

        assertEquals(
            StartCommandDecision.StartNow,
            coalescer.onStart(TorrentDaemon.DaemonState.Stopped, userInitiated = false)
        )
        assertEquals(
            StartCommandDecision.Ignored,
            coalescer.onStart(TorrentDaemon.DaemonState.Starting, userInitiated = false)
        )
        assertFalse(coalescer.takePendingAfterStop(stopSucceeded = true) == true)
    }
}
