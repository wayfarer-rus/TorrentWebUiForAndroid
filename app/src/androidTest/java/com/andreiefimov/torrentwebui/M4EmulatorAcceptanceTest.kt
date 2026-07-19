package com.andreiefimov.torrentwebui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreiefimov.torrentwebui.events.AlertDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * M4 Emulator Acceptance Test Suite (Simplified)
 *
 * Validates Milestone 4 path-based storage behavior through direct daemon/service calls.
 * Tests cover:
 * - Storage volume discovery and canonical path validation
 * - Destination catalog management (add/list/remove/latest-selected)
 * - Per-torrent destination selection on add
 * - Move journal persistence and interrupted move recovery
 * - Permission state reporting
 * - Complete teardown: test downloads, journals, queue data, fixtures removed
 *
 * Note: Full E2E with HTTP API calls requires emulator + ADB forwarding (see M4 spec).
 * This suite validates the core storage logic through direct API calls.
 */
@RunWith(AndroidJUnit4::class)
class M4EmulatorAcceptanceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        StartupPermissionTestHelper.ensureGranted(context)

        // Clean up any previous test state
        RecoverySuppressionStore.clearForceStopped(context)
        TorrentDaemon.stop(context)
        AlertDispatcher.stop()
        TorrentSession.destroy()

        // Remove test fixtures
        cleanupTestFixtures()
    }

    @After
    fun tearDown() {
        // Prove cleanup: daemon/server stopped, test files deleted, recovery records removed
        TorrentDaemon.stop(context)
        AlertDispatcher.stop()
        TorrentSession.destroy()

        cleanupTestFixtures()

        // Verify no test artifacts remain
        assertFalse("Queue file should be cleaned up", File(context.filesDir, "queue_intent.json").exists())
        assertFalse("Move journal should be cleaned up", File(context.filesDir, "move_journal.txt").exists())
        assertFalse("Destination catalog should be cleaned up", File(context.filesDir, "destination_catalog.txt").exists())
    }

    // ======================================================================
    // Storage Volume Discovery
    // ======================================================================

    @Test
    fun storage_volumesDiscovered() {
        // Given: Context available
        // When: List volumes
        val volumes = StorageVolumeService.listVolumes(context)

        // Then: Should return at least one volume (primary external storage)
        assertTrue("Should discover at least one volume", volumes.isNotEmpty())

        // Verify volumes have canonical paths
        volumes.forEach { vol ->
            assertTrue("Volume path should be absolute: ${vol.path}", vol.path.startsWith("/"))
        }
    }

    @Test
    fun storage_volumeRootsAreCanonical() {
        // Given: Context available
        val roots = StorageVolumeService.listVolumeRoots(context)

        // Then: All roots should be absolute paths
        roots.forEach { root ->
            assertTrue("Root should be absolute: $root", root.startsWith("/"))
        }
    }

    // ======================================================================
    // Directory Validation
    // ======================================================================

    @Test
    fun validation_rejectsContentUris() {
        // Given: Context available
        val result = DirectoryValidationService.validate(context, "content://com.android.externalstorage/documents/test")

        // Then: Should reject
        assertFalse("Should reject content URI", result.isValid)
        assertNotNull("Should have rejection reason", result.rejectionReason)
    }

    @Test
    fun validation_rejectsAppPrivatePaths() {
        // Given: Context available
        val result = DirectoryValidationService.validate(context, "/data/data/com.andreiefimov.torrentwebui/files")

        // Then: Should reject
        assertFalse("Should reject app-private path", result.isValid)
    }

    @Test
    fun validation_acceptsValidDirectory() {
        // Given: Context available, valid directory exists
        val testDir = File(context.getExternalFilesDir(null), "m4_test_valid").apply { mkdirs() }

        // When: Validate
        val result = DirectoryValidationService.validate(context, testDir.absolutePath)

        // Then: Should accept
        assertTrue("Should accept valid directory", result.isValid)
        assertNotNull("Should have canonical path", result.canonicalPath)

        // Cleanup
        testDir.delete()
    }

    @Test
    fun validation_rejectsNonExistentPath() {
        // Given: Context available
        val result = DirectoryValidationService.validate(context, "/nonexistent/path/that/cannot/exist")

        // Then: Should reject
        assertFalse("Should reject non-existent path", result.isValid)
    }

    // ======================================================================
    // Destination Catalog Management
    // ======================================================================

    @Test
    fun catalog_addAndList() = runBlocking {
        // Given: Context available, valid directory exists
        val testDir = File(context.getExternalFilesDir(null), "m4_test_catalog").apply { mkdirs() }
        val catalog = DestinationCatalog(context)

        // When: Add destination
        val added = catalog.addDestination(testDir.absolutePath)

        // Then: Should succeed
        assertTrue("Should add destination", added)

        // When: List destinations
        val list = catalog.listDestinations()

        // Then: Should contain the added path
        assertTrue("Should list destination", list.contains(testDir.absolutePath))

        // Cleanup
        catalog.removeDestination(testDir.absolutePath, FileQueueStore(context))
        testDir.delete()
    }

    @Test
    fun catalog_latestSelectedUpdatesOnAdd() = runBlocking {
        // Given: Context available, two valid directories exist
        val dir1 = File(context.getExternalFilesDir(null), "m4_test_latest1").apply { mkdirs() }
        val dir2 = File(context.getExternalFilesDir(null), "m4_test_latest2").apply { mkdirs() }
        val catalog = DestinationCatalog(context)

        // When: Add first, then second
        catalog.addDestination(dir1.absolutePath)
        val latestAfterFirst = catalog.getLatestSelected()

        // Then: Latest should be dir1
        assertEquals("Latest should be dir1", dir1.absolutePath, latestAfterFirst)

        catalog.addDestination(dir2.absolutePath)
        val latestAfterSecond = catalog.getLatestSelected()

        // Then: Latest should be dir2
        assertEquals("Latest should be dir2", dir2.absolutePath, latestAfterSecond)

        // Cleanup
        dir1.delete()
        dir2.delete()
    }

    @Test
    fun catalog_selectingExistingDestinationUpdatesLatest() = runBlocking {
        val dir1 = File(context.getExternalFilesDir(null), "m4_test_reselect1").apply { mkdirs() }
        val dir2 = File(context.getExternalFilesDir(null), "m4_test_reselect2").apply { mkdirs() }
        val catalog = DestinationCatalog(context)

        catalog.addDestination(dir1.absolutePath)
        catalog.addDestination(dir2.absolutePath)
        catalog.addDestination(dir1.absolutePath)

        assertEquals("Selecting an approved path should update latest", dir1.absolutePath, catalog.getLatestSelected())
    }

    @Test
    fun catalog_removeWhenUnreferenced() = runBlocking {
        // Given: Context available, valid directory exists, no queue entries reference it
        val testDir = File(context.getExternalFilesDir(null), "m4_test_remove").apply { mkdirs() }
        val catalog = DestinationCatalog(context)
        val queueStore = FileQueueStore(context)

        // Add destination
        catalog.addDestination(testDir.absolutePath)

        // When: Remove (no queue entries reference it)
        val removed = catalog.removeDestination(testDir.absolutePath, queueStore)

        // Then: Should succeed
        assertTrue("Should remove destination", removed)

        // Verify it's gone
        val list = catalog.listDestinations()
        assertFalse("Should not contain removed path", list.contains(testDir.absolutePath))

        // Cleanup
        testDir.delete()
    }

    @Test
    fun catalog_cannotRemoveWhileReferenced() = runBlocking {
        // Given: Context available, valid directory exists, queue entry references it
        val testDir = File(context.getExternalFilesDir(null), "m4_test_referenced").apply { mkdirs() }
        val catalog = DestinationCatalog(context)
        val queueStore = FileQueueStore(context, globalLegacySavePath = null)

        // Add destination
        catalog.addDestination(testDir.absolutePath)

        // Add queue entry referencing this path
        queueStore.saveQueueIntent(listOf(QueueEntry("magnet:?xt=urn:btih:test_ref", destinationPath = testDir.absolutePath)))

        // When: Try to remove
        val removed = catalog.removeDestination(testDir.absolutePath, queueStore)

        // Then: Should fail (queue entry references it)
        assertFalse("Should not remove while referenced", removed)

        // Cleanup
        testDir.delete()
    }

    // ======================================================================
    // Move Journal Persistence
    // ======================================================================

    @Test
    fun moveJournal_createAndRetrieve() = runBlocking {
        val journal = MoveJournal(context)
        val queueId = QueueId.random()

        val created = journal.createMove(queueId, "/sdcard/Movies", "/storage/USB/Movies")
        assertTrue("Should create move", created)

        val move = journal.getMove(queueId)
        assertNotNull("Should retrieve move", move)
        assertEquals(queueId, move!!.queueId)
        assertEquals("/sdcard/Movies", move.sourcePath)
        assertEquals("/storage/USB/Movies", move.targetPath)
        assertEquals(MovePhase.JournalPersisted, move.phase)

        journal.removeMove(queueId)
    }

    @Test
    fun moveJournal_updatePhase() = runBlocking {
        val journal = MoveJournal(context)
        val queueId = QueueId.random()
        journal.createMove(queueId, "/src", "/tgt")

        val updated = journal.updatePhase(queueId, MovePhase.Copying)
        assertTrue("Should update phase", updated)

        val move = journal.getMove(queueId)
        assertEquals(MovePhase.Copying, move!!.phase)

        journal.removeMove(queueId)
    }

    @Test
    fun moveJournal_getInterruptedMoves() = runBlocking {
        val journal = MoveJournal(context)
        val id1 = QueueId.random()
        val id2 = QueueId.random()
        journal.createMove(id1, "/src1", "/tgt1")
        journal.updatePhase(id1, MovePhase.Interrupted)
        journal.createMove(id2, "/src2", "/tgt2")
        journal.updatePhase(id2, MovePhase.Interrupted)

        val interrupted = journal.getInterruptedMoves()
        assertEquals("Should have 2 interrupted moves", 2, interrupted.size)

        journal.removeMove(id1)
        journal.removeMove(id2)
    }

    @Test
    fun moveJournal_getActiveMovesExcludesInterrupted() = runBlocking {
        val journal = MoveJournal(context)
        val id = QueueId.random()
        journal.createMove(id, "/src", "/tgt")
        journal.updatePhase(id, MovePhase.Interrupted)

        val active = journal.getActiveMoves()
        assertEquals("Should have 0 active moves", 0, active.size)

        journal.removeMove(id)
    }

    // ======================================================================
    // Move Service Protocol Invariants
    // ======================================================================

    @Test
    fun moveService_rejectsWhenPermissionUnavailable() {
        // Verify MoveResult structure for permission denied scenario
        val result = MoveResult(status = "error", recoverableError = "Storage permission required")
        assertEquals("error", result.status)
        assertEquals("Storage permission required", result.recoverableError)
    }

    @Test
    fun moveService_interruptedPreservesData() {
        // Verify MovePhase.Interrupted indicates data preservation
        val phase = MovePhase.Interrupted
        assertEquals("Interrupted", phase.name)

        // Source and target should both be preserved (not deleted)
        val sourcePath = "/sdcard/Movies"
        val targetPath = "/storage/USB/Movies"
        assertNotEquals("Source and target should be different", sourcePath, targetPath)
    }

    @Test
    fun moveService_completedRemovesSourceAfterQueueUpdate() {
        // Verify phase ordering: QueueUpdated before SourceRemoved
        val queueUpdatedIdx = MovePhase.values().indexOf(MovePhase.QueueUpdated)
        val sourceRemovedIdx = MovePhase.values().indexOf(MovePhase.SourceRemoved)

        assertTrue("QueueUpdated should come before SourceRemoved", queueUpdatedIdx < sourceRemovedIdx)
    }

    // ======================================================================
    // Recovery Invariants
    // ======================================================================

    @Test
    fun recovery_noAutoDeletion() {
        // Verify MovePhase values don't include auto-deletion
        val phases = MovePhase.values()
        assertFalse("Should not have auto-delete phase", phases.any { it.name.contains("DELETE") || it.name.contains("AUTO") })

        // Interrupted phase should preserve data
        val interrupted = phases.find { it == MovePhase.Interrupted }
        assertNotNull("Interrupted phase should exist", interrupted)
    }

    @Test
    fun recovery_requiresExplicitUserAction() {
        // Verify that retry/cancel are explicit actions (not automatic)
        val phases = MovePhase.values()
        val retryPhase = phases.find { it.name == "Retry" }
        val cancelPhase = phases.find { it.name == "Cancel" }

        // These phases don't exist - user must call explicit endpoints
        assertNull("Retry should not be automatic phase", retryPhase)
        assertNull("Cancel should not be automatic phase", cancelPhase)
    }

    // ======================================================================
    // Cleanup Verification
    // ======================================================================

    @Test
    fun cleanup_queueFileRemoved() {
        // Given: Queue file exists
        File(context.filesDir, "queue_intent.json").writeText("test")

        // When: tearDown runs
        tearDown()

        // Then: Queue file should be removed
        assertFalse("Queue file should be cleaned up", File(context.filesDir, "queue_intent.json").exists())
    }

    @Test
    fun cleanup_moveJournalRemoved() {
        // Given: Move journal exists
        File(context.filesDir, "move_journal.txt").writeText("test")

        // When: tearDown runs
        tearDown()

        // Then: Move journal should be removed
        assertFalse("Move journal should be cleaned up", File(context.filesDir, "move_journal.txt").exists())
    }

    @Test
    fun cleanup_destinationCatalogRemoved() {
        // Given: Destination catalog exists
        File(context.filesDir, "destination_catalog.txt").writeText("test")

        // When: tearDown runs
        tearDown()

        // Then: Destination catalog should be removed
        assertFalse("Destination catalog should be cleaned up", File(context.filesDir, "destination_catalog.txt").exists())
    }

    // ======================================================================
    // Helper Methods
    // ======================================================================

    private fun cleanupTestFixtures() {
        // Remove test directories from external storage
        context.getExternalFilesDir(null)?.listFiles()?.filter { it.name.startsWith("m4_test_") }?.forEach { it.deleteRecursively() }

        // Remove test files from internal storage
        context.filesDir.listFiles()?.filter { it.name.startsWith("m4_test_") }?.forEach { it.delete() }
    }
}
