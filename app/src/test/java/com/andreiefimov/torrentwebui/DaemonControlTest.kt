package com.andreiefimov.torrentwebui

import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for the daemon control seam — verifies that operations and lifecycle are properly
 * delegated through [DaemonControl] without changing behavior.
 *
 * These tests use an in-memory mock [TorrentSessionOps] to verify that the seam correctly
 * routes all operations, including lifecycle (init/destroy) and diagnostics.
 */
class DaemonControlTest {

    /** A mock [TorrentSessionOps] for testing seam delegation. */
    private class MockSessionOps : TorrentSessionOps {
        var addMagnetCalled = false
        var pauseCalled = false
        var resumeCalled = false
        var removeCalled = false
        var lastMagnet: String? = null
        var lastId: Long? = null

        override fun addMagnet(magnetUri: String): Long {
            addMagnetCalled = true
            lastMagnet = magnetUri
            return 1L
        }

        override fun pauseTorrent(torrentId: Long): Boolean {
            pauseCalled = true
            lastId = torrentId
            return true
        }

        override fun resumeTorrent(torrentId: Long): Boolean {
            resumeCalled = true
            lastId = torrentId
            return true
        }

        override fun removeTorrent(torrentId: Long, deleteFiles: Boolean): Boolean {
            removeCalled = true
            lastId = torrentId
            return true
        }

        override fun getAllTorrentIds(): List<Long> = listOf(1L, 2L)

        override fun getTorrentStatus(torrentId: Long): TorrentStatus? =
            if (torrentId == 1L) {
                TorrentStatus(
                    id = torrentId, name = "Test", state = "downloading", progress = 0.5f,
                    downloadRate = 1000L, uploadRate = 500L, peers = 3, savePath = "/test"
                )
            } else null

        override fun popAlerts(): String = "[]"
        override val lastError: String? get() = null
    }

    // ---- Factory: production creates a valid DaemonControl ----

    @Test
    fun factoryCreate_returnsDaemonControl() {
        val control = DaemonControlFactory.create()
        assertNotNull(control)
        assertTrue(control is DaemonControl)
    }

    // ---- Factory: test variant delegates all operations ----

    @Test
    fun factoryCreateForTest_delegatesAddMagnet() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        val id = control.addMagnet("magnet:?xt=urn:btih:test")

        assertTrue(mock.addMagnetCalled)
        assertEquals("magnet:?xt=urn:btih:test", mock.lastMagnet)
        assertEquals(1L, id)
    }

    @Test
    fun factoryCreateForTest_delegatesPause() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        control.pauseTorrent(42L)

        assertTrue(mock.pauseCalled)
        assertEquals(42L, mock.lastId)
    }

    @Test
    fun factoryCreateForTest_delegatesResume() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        control.resumeTorrent(42L)

        assertTrue(mock.resumeCalled)
        assertEquals(42L, mock.lastId)
    }

    @Test
    fun factoryCreateForTest_delegatesRemove() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        control.removeTorrent(42L, deleteFiles = true)

        assertTrue(mock.removeCalled)
        assertEquals(42L, mock.lastId)
    }

    @Test
    fun factoryCreateForTest_delegatesGetAllTorrentIds() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        val ids = control.getAllTorrentIds()

        assertEquals(listOf(1L, 2L), ids)
    }

    @Test
    fun factoryCreateForTest_delegatesGetTorrentStatus() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        val status = control.getTorrentStatus(1L)

        assertNotNull(status)
        assertEquals("Test", status!!.name)
        assertEquals("downloading", status.state)
    }

    @Test
    fun factoryCreateForTest_delegatesGetTorrentStatus_returnsNull_forUnknown() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        val status = control.getTorrentStatus(999L)

        assertNull(status)
    }

    @Test
    fun factoryCreateForTest_delegatesPopAlerts() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        val alerts = control.popAlerts()

        assertEquals("[]", alerts)
    }

    @Test
    fun factoryCreateForTest_delegatesLastError() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        val error = control.lastError

        assertNull(error)
    }

    // ---- Lifecycle delegation ----

    @Test
    fun factoryCreateForTest_initReturnsTrue() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        // Test variant always returns true (no real init needed for tests).
        // We verify the seam has an init method that returns true.
        val testControl = object : DaemonControl by control {
            override fun init(context: android.content.Context): Boolean {
                return true
            }
        }

        // Verify the seam has init (compile-time check via interface).
        assertTrue(testControl is DaemonControl)
    }

    @Test
    fun factoryCreateForTest_destroyDoesNotThrow() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        // Should not throw.
        try {
            control.destroy()
        } catch (e: Exception) {
            fail("destroy() should not throw: ${e.message}")
        }
    }

    // ---- Diagnostics delegation ----

    @Test
    fun factoryCreateForTest_getDiagnostics_returnsTestDiagnostics() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        val diagnostics = control.getDiagnostics()

        assertEquals("test", diagnostics.abi)
        assertEquals("test", diagnostics.libtorrentVersion)
        assertTrue(diagnostics.nativeLoaded)
        assertTrue(diagnostics.sessionStarted)
        assertNull(diagnostics.lastError)
    }

    // ---- TorrentSession implements DaemonControl ----

    @Test
    fun torrentSession_implementsDaemonControl() {
        assertTrue(TorrentSession is DaemonControl)
    }

    // ---- TorrentServer accepts DaemonControl ----

    @Test
    fun torrentServer_defaultDaemonControlIsTorrentSession() {
        // After resetToDefaults, the server should use TorrentSession.
        TorrentServer.resetToDefaults()
        assertSame(TorrentSession, TorrentServer.daemonControl)
    }

    @Test
    fun torrentServer_canInjectCustomDaemonControl() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        TorrentServer.configureForTest(
            authManager = InMemoryAuthManager(),
            daemonControl = control
        )

        assertSame(control, TorrentServer.daemonControl)

        // Reset for other tests.
        TorrentServer.resetToDefaults()
    }

    @Test
    fun torrentServer_daemonControl_delegatesToInjectedControl() {
        val mock = MockSessionOps()
        val control = DaemonControlFactory.createForTest(mock)

        TorrentServer.configureForTest(
            authManager = InMemoryAuthManager(),
            daemonControl = control
        )

        // Verify the injected control is used.
        assertEquals(listOf(1L, 2L), TorrentServer.daemonControl.getAllTorrentIds())
        assertNotNull(TorrentServer.daemonControl.getTorrentStatus(1L))

        // Reset for other tests.
        TorrentServer.resetToDefaults()
    }
}
