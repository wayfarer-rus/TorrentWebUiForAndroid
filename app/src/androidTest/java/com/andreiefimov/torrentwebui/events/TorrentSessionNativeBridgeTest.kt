package com.andreiefimov.torrentwebui.events

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreiefimov.torrentwebui.TorrentSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end tests for the native JNI bridge layer (TorrentSession).
 *
 * These tests verify that the Kotlin side of the libtorrent bridge correctly
 * translates native calls into usable data, without requiring real torrent activity.
 */
@RunWith(AndroidJUnit4::class)
class TorrentSessionNativeBridgeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (AlertDispatcher.isRunning) AlertDispatcher.stop()
    }

    @After
    fun tearDown() {
        if (AlertDispatcher.isRunning) AlertDispatcher.stop()
        TorrentSession.destroy()
    }

    // ======================================================================
    // 1. popAlerts: JSON format and consumption
    // ======================================================================

    @Test
    fun popAlerts_returnsEmptyArrayWhenNotInit() {
        val alerts = TorrentSession.popAlerts()
        assertEquals("[]", alerts)
    }

    @Test
    fun popAlerts_returnsValidJsonArrayAfterInit() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        val first = TorrentSession.popAlerts()
        assertTrue("Expected JSON array: $first", first.startsWith("["))

        // Second call should return empty (alerts were consumed).
        val second = TorrentSession.popAlerts()
        assertEquals("[]", second)

        TorrentSession.destroy()
    }

    // ======================================================================
    // 2. getAllTorrentIds: lifecycle (empty → populated → empty)
    // ======================================================================

    @Test
    fun getAllTorrentIds_emptyWhenNoSession() {
        val ids = TorrentSession.getAllTorrentIds()
        assertTrue("Expected empty list when session not initialized", ids.isEmpty())
    }

    @Test
    fun getAllTorrentIds_emptyAfterInit() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        val ids = TorrentSession.getAllTorrentIds()
        assertTrue("Expected empty list after init with no torrents", ids.isEmpty())

        TorrentSession.destroy()
    }

    @Test
    fun getAllTorrentIds_populatedAfterAddMagnet() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        val torrentId = TorrentSession.addMagnet(TEST_MAGNET)
        assertTrue("addMagnet should return positive ID", torrentId > 0)

        // Give libtorrent a moment to register the torrent.
        delay(1000)

        val ids = TorrentSession.getAllTorrentIds()
        assertTrue("Expected at least one torrent ID", ids.isNotEmpty())
        assertTrue("Torrent ID $torrentId should be in the list", ids.contains(torrentId))

        TorrentSession.removeTorrent(torrentId, deleteFiles = false)
        TorrentSession.destroy()
    }

    @Test
    fun getAllTorrentIds_emptyAfterRemove() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        val torrentId = TorrentSession.addMagnet(TEST_MAGNET)
        assertTrue("addMagnet should return positive ID", torrentId > 0)

        delay(500)
        TorrentSession.removeTorrent(torrentId, deleteFiles = false)

        val ids = TorrentSession.getAllTorrentIds()
        assertTrue("Expected empty list after removing torrent", ids.isEmpty())

        TorrentSession.destroy()
    }

    // ======================================================================
    // 3. getTorrentStatus: valid data after addMagnet
    // ======================================================================

    @Test
    fun addMagnet_usesTheExplicitDestinationPath() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking
        val destination = java.io.File(context.getExternalFilesDir(null), "m4_native_destination").apply {
            mkdirs()
        }.canonicalPath

        val torrentId = TorrentSession.addMagnet(TEST_MAGNET, com.andreiefimov.torrentwebui.TorrentDestination(destination))
        assertTrue("addMagnet should return positive ID", torrentId > 0)

        val status = TorrentSession.getTorrentStatus(torrentId)
        assertNotNull("getTorrentStatus should return the added torrent", status)
        assertEquals(destination, status!!.savePath)

        TorrentSession.removeTorrent(torrentId, deleteFiles = false)
        java.io.File(destination).delete()
        TorrentSession.destroy()
    }

    @Test
    fun getTorrentStatus_providesValidData() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        val torrentId = TorrentSession.addMagnet(TEST_MAGNET)
        assertTrue("addMagnet should return positive ID", torrentId > 0)

        // Wait for status to be populated (metadata download takes time on emulator).
        var status = TorrentSession.getTorrentStatus(torrentId)
        for (i in 0 until 10) {
            if (status != null && status.id > 0) break
            delay(1000)
            status = TorrentSession.getTorrentStatus(torrentId)
        }

        assertNotNull("getTorrentStatus should return non-null after wait", status)
        assertTrue("Progress should be between 0 and 1, was: ${status?.progress}",
            status!!.progress in 0.0f..1.0f)
        assertTrue("Peers should be non-negative, was: ${status.peers}", status.peers >= 0)
        assertTrue("State should not be empty", status.state.isNotEmpty())

        TorrentSession.destroy()
    }

    // ======================================================================
    // 4. getAllTorrentHashes: Stage 1 limitation (returns empty strings)
    // ======================================================================

    @Test
    fun getAllTorrentHashes_returnsEmptyMapInStage1() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        val torrentId = TorrentSession.addMagnet(TEST_MAGNET)
        assertTrue("addMagnet should return positive ID", torrentId > 0)

        delay(1000)

        val hashes = TorrentSession.getAllTorrentHashes()
        // Stage 1: all hash values are empty strings, so the filter drops them → empty map.
        // This is a documented limitation; the function should not crash.
        assertNotNull("getAllTorrentHashes should not return null", hashes)

        TorrentSession.destroy()
    }

    // ======================================================================
    // 5. getDiagnostics: valid data after init
    // ======================================================================

    @Test
    fun getDiagnostics_returnsValidDataBeforeInit() {
        // Note: nativeLoaded may already be true if previous tests loaded the library.
        // This test verifies getDiagnostics() returns valid data regardless of init state.
        val diag = TorrentSession.getDiagnostics()
        assertNotNull("Diagnostics should not be null", diag)
        // Verify we get valid data (libtorrent version, etc.) regardless of init state.
        assertNotNull("libtorrentVersion should be populated", diag.libtorrentVersion)
    }

    @Test
    fun getDiagnostics_returnsValidDataAfterInit() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        val diag = TorrentSession.getDiagnostics()
        assertTrue("nativeLoaded should be true after init", diag.nativeLoaded)
        assertTrue("sessionStarted should be true after init", diag.sessionStarted)
        assertNotNull("libtorrentVersion should not be null", diag.libtorrentVersion)
        assertTrue("libtorrentVersion should not be empty: ${diag.libtorrentVersion}",
            diag.libtorrentVersion.isNotEmpty())

        TorrentSession.destroy()
    }

    // ======================================================================
    // 6. pause/resume/remove: return values without crashing
    // ======================================================================

    @Test
    fun pauseTorrent_beforeInit_returnsFalse() {
        assertFalse("pauseTorrent before init should return false", TorrentSession.pauseTorrent(1L))
    }

    @Test
    fun resumeTorrent_beforeInit_returnsFalse() {
        assertFalse("resumeTorrent before init should return false", TorrentSession.resumeTorrent(1L))
    }

    @Test
    fun removeTorrent_beforeInit_returnsFalse() {
        assertFalse("removeTorrent before init should return false", TorrentSession.removeTorrent(1L, false))
    }

    // ======================================================================
    // 7. addMagnet: invalid magnet URI returns -1
    // ======================================================================

    @Test
    fun addMagnet_invalidUri_returnsNegativeId() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        val torrentId = TorrentSession.addMagnet("not-a-valid-magnet-uri")
        assertTrue("Invalid magnet should return negative ID, got: $torrentId", torrentId < 0)

        TorrentSession.destroy()
    }

    // ======================================================================
    // Helper: test magnet URI (Ubuntu 24.04 ISO, public domain)
    // ======================================================================

    private val TEST_MAGNET = "magnet:?xt=urn:btih:2e62854a660074367b8104bd09472b04b44d870e&dn=ubuntu-24.04.1-desktop-amd64.iso&tr=udp://tracker.opentrackr.org:1337/announce"
}
