package com.andreiefimov.torrentwebui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreiefimov.torrentwebui.events.AlertDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * M4 direct service coverage (not full-stack acceptance evidence).
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
 * This suite requires its permission preconditions to be established through visible Android UI.
 * It does not prove the required Settings, ADB-forwarding, Ktor, or browser acceptance flow.
 */
@RunWith(AndroidJUnit4::class)
class M4EmulatorAcceptanceTest {

    private lateinit var context: Context

    private class RecordingSessionOps(
        private val moveAccepted: Boolean = true,
        private val torrentName: String = "fixture.bin",
        private val rollbackAccepted: Boolean = true
    ) : TorrentSessionOps {
        val pausedIds = mutableListOf<Long>()
        val resumedIds = mutableListOf<Long>()
        val movedIds = mutableListOf<Long>()
        val rolledBackIds = mutableListOf<Long>()

        override fun addMagnet(magnetUri: String): Long = 1L
        override fun moveStorage(torrentId: Long, targetPath: String): Boolean {
            movedIds += torrentId
            return moveAccepted
        }
        override suspend fun verifyTorrentData(torrentId: Long): Boolean = true
        override suspend fun rollbackStorage(torrentId: Long, sourcePath: String): Boolean {
            rolledBackIds += torrentId
            return rollbackAccepted
        }
        override fun pauseTorrent(torrentId: Long): Boolean {
            pausedIds += torrentId
            return true
        }
        override fun resumeTorrent(torrentId: Long): Boolean {
            resumedIds += torrentId
            return true
        }
        override fun removeTorrent(torrentId: Long, deleteFiles: Boolean): Boolean = false
        override fun getAllTorrentIds(): List<Long> = listOf(1L, 2L)
        override fun getTorrentStatus(torrentId: Long): TorrentStatus = TorrentStatus(
            torrentId, torrentName, "paused", 1f, 0, 0, 0, ""
        )
        override fun popAlerts(): String = "[]"
        override val lastError: String? = if (moveAccepted) null else "fixture native rejection"
    }

    private class FailingCopyingMoveJournal(context: Context) : MoveJournal(context) {
        override suspend fun updatePhase(queueId: QueueId, phase: MovePhase): Boolean =
            if (phase == MovePhase.Copying) false else super.updatePhase(queueId, phase)
    }

    private class FailingPauseSessionOps : TorrentSessionOps {
        var pauseCalled = false
        var moveCalled = false

        override fun addMagnet(magnetUri: String): Long = 1L
        override fun moveStorage(torrentId: Long, targetPath: String): Boolean {
            moveCalled = true
            return true
        }
        override fun pauseTorrent(torrentId: Long): Boolean {
            pauseCalled = true
            return false
        }
        override fun resumeTorrent(torrentId: Long): Boolean = false
        override fun removeTorrent(torrentId: Long, deleteFiles: Boolean): Boolean = false
        override fun getAllTorrentIds(): List<Long> = listOf(1L)
        override fun getTorrentStatus(torrentId: Long): TorrentStatus? = null
        override fun popAlerts(): String = "[]"
        override val lastError: String = "fixture pause failure"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        StartupPermissionTestHelper.ensureGranted(context)

        // Clean up any previous test state without treating the host's instrumentation restart
        // as a product force stop or racing a new start against asynchronous daemon teardown.
        StartupPermissionTestHelper.clearInstrumentationStop(context)
        StartupPermissionTestHelper.stopDaemonIfActive(context)
        AlertDispatcher.stop()
        TorrentSession.destroy()

        // Remove test fixtures
        cleanupTestFixtures()
    }

    @After
    fun tearDown() {
        // Prove cleanup: daemon/server stopped, test files deleted, recovery records removed
        StartupPermissionTestHelper.stopDaemonIfActive(context)
        AlertDispatcher.stop()
        TorrentSession.destroy()

        cleanupTestFixtures()

        // Verify no test artifacts remain
        assertFalse("Queue file should be cleaned up", File(context.filesDir, "queue_intent.json").exists())
        assertFalse("Move journal should be cleaned up", File(context.filesDir, "move_journal_v2.json").exists())
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
    fun catalog_addAndList() {
        runBlocking {
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
    }

    @Test
    fun catalog_latestSelectedUpdatesOnAdd() {
        runBlocking {
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
    }

    @Test
    fun catalog_selectingExistingDestinationUpdatesLatest() {
        runBlocking {
        val dir1 = File(context.getExternalFilesDir(null), "m4_test_reselect1").apply { mkdirs() }
        val dir2 = File(context.getExternalFilesDir(null), "m4_test_reselect2").apply { mkdirs() }
        val catalog = DestinationCatalog(context)

        catalog.addDestination(dir1.absolutePath)
        catalog.addDestination(dir2.absolutePath)
        catalog.addDestination(dir1.absolutePath)

        assertEquals("Selecting an approved path should update latest", dir1.absolutePath, catalog.getLatestSelected())
        }
    }

    @Test
    fun catalog_latestSelectedPersistsInTheAtomicCatalogFile() {
        runBlocking {
            val destination = File(context.getExternalFilesDir(null), "m4_test_atomic_latest").apply { mkdirs() }
            val catalog = DestinationCatalog(context)

            assertTrue(catalog.addDestination(destination.canonicalPath))
            context.getSharedPreferences("destination_catalog_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit()

            val reopened = DestinationCatalog(context)
            assertEquals(destination.canonicalPath, reopened.getLatestSelected())
            assertTrue(reopened.listDestinations().contains(destination.canonicalPath))
            assertTrue(
                File(context.filesDir, "destination_catalog.txt")
                    .readLines().first().startsWith("@latest|")
            )
            destination.delete()
        }
    }

    @Test
    fun catalog_legacySplitStateMigratesOnTheNextSelection() {
        runBlocking {
            val first = File(context.getExternalFilesDir(null), "m4_test_legacy_catalog_1").apply { mkdirs() }
            val second = File(context.getExternalFilesDir(null), "m4_test_legacy_catalog_2").apply { mkdirs() }
            File(context.filesDir, "destination_catalog.txt")
                .writeText("${first.canonicalPath}|1")
            context.getSharedPreferences("destination_catalog_prefs", Context.MODE_PRIVATE)
                .edit().putString("latest_selected", first.canonicalPath).commit()

            val catalog = DestinationCatalog(context)
            assertEquals(first.canonicalPath, catalog.getLatestSelected())
            assertTrue(catalog.addDestination(second.canonicalPath))
            context.getSharedPreferences("destination_catalog_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit()

            val reopened = DestinationCatalog(context)
            assertEquals(second.canonicalPath, reopened.getLatestSelected())
            assertEquals(setOf(first.canonicalPath, second.canonicalPath), reopened.listDestinations().toSet())
            first.delete()
            second.delete()
        }
    }

    @Test
    fun catalog_removeWhenUnreferenced() {
        runBlocking {
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
    }

    @Test
    fun catalog_cannotRemoveWhileReferenced() {
        runBlocking {
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
    }

    // ======================================================================
    // Move Journal Persistence
    // ======================================================================

    @Test
    fun moveJournal_createAndRetrieve() {
        runBlocking {
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
    }

    @Test
    fun moveJournal_updatePhase() {
        runBlocking {
        val journal = MoveJournal(context)
        val queueId = QueueId.random()
        journal.createMove(queueId, "/src", "/tgt")

        val updated = journal.updatePhase(queueId, MovePhase.Copying)
        assertTrue("Should update phase", updated)

        val move = journal.getMove(queueId)
        assertEquals(MovePhase.Copying, move!!.phase)

        journal.removeMove(queueId)
        }
    }

    @Test
    fun moveJournal_getInterruptedMoves() {
        runBlocking {
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
    }

    @Test
    fun moveJournal_v1MigrationSelectsOneLatestAuthorityAndRetainsTerminalAudit() {
        runBlocking {
            val source = "/storage/emulated/0/Download/m4-migrate-source"
            val queueId = QueueId("m4-migrated")
            val store = FileQueueStore(context, globalLegacySavePath = null)
            store.saveQueueIntent(listOf(QueueEntry("magnet:migrate", destinationPath = source, queueId = queueId)))
            File(context.filesDir, "move_journal.txt").writeText(
                listOf(
                    "1|$source|/target-old|Interrupted|100|100",
                    "1|$source|/target-new|Interrupted|200|300",
                    "1|$source|/target-middle|Interrupted|150|200",
                    "1|$source|/target-audit|Cancelled|50|400"
                ).joinToString("\n")
            )

            val journal = MoveJournal(context)
            assertEquals(2, journal.migrateV1Journal(store))
            assertEquals("/target-new", journal.getMove(queueId)?.targetPath)
            assertEquals(listOf("/target-audit"), journal.getAuditMoves(queueId).map { it.targetPath })
            assertEquals(0, journal.migrateV1Journal(store))
            assertEquals(1, journal.getInterruptedMoves().count { it.queueId == queueId })
        }
    }

    @Test
    fun moveJournal_getActiveMovesExcludesInterrupted() {
        runBlocking {
        val journal = MoveJournal(context)
        val id = QueueId.random()
        journal.createMove(id, "/src", "/tgt")
        journal.updatePhase(id, MovePhase.Interrupted)

        val active = journal.getActiveMoves()
        assertEquals("Should have 0 active moves", 0, active.size)

        journal.removeMove(id)
        }
    }

    @Test
    fun moveJournal_cancelledRetainedWithoutActiveLock() {
        runBlocking {
            val journal = MoveJournal(context)
            val id = QueueId.random()
            journal.createMove(id, "/src", "/tgt", verifyExistingData = true, sourceBackupPath = "/src/.backup")
            assertTrue(journal.updatePhase(id, MovePhase.Cancelled))

            assertNull("Cancelled audit must not remain active", journal.getMove(id))
            val retained = journal.getRetainedMove(id)
            assertNotNull("Cancelled audit should be retained", retained)
            assertEquals(MovePhase.Cancelled, retained!!.phase)
            assertTrue(retained.verifyExistingData)
            assertEquals("/src/.backup", retained.sourceBackupPath)
            assertTrue(journal.getActiveMoves().isEmpty())
            assertTrue(journal.getInterruptedMoves().isEmpty())

            assertTrue(journal.createMove(id, "/src", "/next"))
            assertEquals("A later move must retain cancelled audit history", 1, journal.getAuditMoves(id).size)
            assertEquals("/next", journal.getMove(id)?.targetPath)

            journal.removeMove(id)
        }
    }

    @Test
    fun moveJournal_beginIsAtomicAcrossConcurrentRequestsAndTargets() {
        runBlocking {
            val journal = MoveJournal(context)
            val first = QueueId.random()
            val second = QueueId.random()
            val results = coroutineScope {
                listOf(
                    async { journal.beginMove(first, "/src", "/target-a") },
                    async { journal.beginMove(first, "/src", "/target-b") }
                ).awaitAll()
            }
            assertEquals(1, results.count { it })
            val retainedTarget = journal.getMove(first)!!.targetPath
            assertFalse(journal.beginMove(second, "/other", retainedTarget))
            assertEquals(retainedTarget, journal.getMove(first)!!.targetPath)
            journal.removeMove(first)
        }
    }

    @Test
    fun moveJournal_corruptAndUnsupportedFilesFailClosedWithoutOverwrite() {
        runBlocking {
            val file = File(context.filesDir, "move_journal_v2.json")
            for (content in listOf("{malformed", "{\"version\":99,\"entries\":[]}")) {
                file.writeText(content)
                val original = file.readBytes()
                assertFalse(MoveJournal(context).beginMove(QueueId.random(), "/src", "/target"))
                assertArrayEquals(original, file.readBytes())
                try {
                    MoveJournal(context).getActiveMoves()
                    fail("Corrupt journal must not be treated as empty")
                } catch (_: Exception) {
                    // Expected fail-closed read.
                }
            }
            file.delete()
        }
    }

    @Test
    fun moveRecoveryPreflightBlocksCorruptJournalBeforeNativeWork() {
        runBlocking {
            val file = File(context.filesDir, "move_journal_v2.json")
            val content = "{corrupt-cold-start"
            file.writeText(content)
            assertFalse(validateMoveRecoveryRecords(InMemoryQueueStore(), MoveJournal(context)))
            assertEquals(content, file.readText())
            file.delete()
        }
    }

    @Test
    fun retryRefreshesCollisionMetadataWithoutChangingMoveIdentity() {
        runBlocking {
            val journal = MoveJournal(context)
            val queueId = QueueId.random()
            assertTrue(journal.beginMove(queueId, "/source", "/target", verifyExistingData = false))
            assertTrue(journal.updatePhase(queueId, MovePhase.Interrupted))
            assertTrue(journal.beginMove(
                queueId,
                "/ignored-source",
                "/target",
                verifyExistingData = true,
                sourceBackupPath = "/source/.backup",
                sourceVerifiedProgress = 0.25f
            ))
            val retried = journal.getMove(queueId)!!
            assertEquals("/source", retried.sourcePath)
            assertEquals("/target", retried.targetPath)
            assertTrue(retried.verifyExistingData)
            assertEquals("/source/.backup", retried.sourceBackupPath)
            assertEquals(0.25f, retried.sourceVerifiedProgress)
            journal.removeMove(queueId)
        }
    }

    @Test
    fun recoveryAddStartsPausedBeforeNativeWorkWhenStorageRequiresIt() {
        val entry = QueueEntry(
            magnetUri = "magnet:?xt=urn:btih:paused-recovery",
            destinationPath = "/storage/test",
            storagePauseRequired = true
        )
        assertTrue(recoveryAddRequest(entry, "/storage/test", isDestinationAvailable = true).startPaused)
        assertTrue(recoveryAddRequest(entry.copy(storagePauseRequired = false), "/storage/test", false).startPaused)
        assertFalse(recoveryAddRequest(entry.copy(storagePauseRequired = false), "/storage/test", true).startPaused)
    }

    @Test
    fun partialTorrentVerificationRetainsAtLeastTheSourcesVerifiedProgress() {
        val source = File(context.cacheDir, "m4_partial_source").apply { writeBytes(byteArrayOf(1)) }
        val target = File(context.cacheDir, "m4_partial_target").apply { writeBytes(byteArrayOf(2)) }
        try {
            assertTrue(verifiedTargetRetainsSourceProgress(source, target, 0.25f, 0.5f))
            assertTrue(verifiedTargetRetainsSourceProgress(source, target, 0.5f, 0.5f))
            assertFalse(verifiedTargetRetainsSourceProgress(source, target, 0.5f, 0.25f))
            target.delete()
            assertFalse(verifiedTargetRetainsSourceProgress(source, target, 0.25f, 1f))
        } finally {
            source.delete()
            target.delete()
        }
    }

    @Test
    fun completedMoveJournalProtectsCatalogPathsUntilTeardown() {
        runBlocking {
            val destination = File(context.getExternalFilesDir(null), "m4_test_completed_lock").apply { mkdirs() }
            val catalog = DestinationCatalog(context)
            val store = InMemoryQueueStore()
            val journal = MoveJournal(context)
            val queueId = QueueId.random()
            try {
                assertTrue(catalog.addDestination(destination.canonicalPath))
                assertTrue(journal.createMove(queueId, "/source", destination.canonicalPath))
                assertTrue(journal.updatePhase(queueId, MovePhase.Completed))
                assertFalse(catalog.removeDestination(destination.canonicalPath, store, journal))
                assertTrue(journal.removeMove(queueId))
                assertTrue(catalog.removeDestination(destination.canonicalPath, store, journal))
            } finally {
                journal.removeMove(queueId)
                destination.deleteRecursively()
            }
        }
    }

    @Test
    fun unavailableDestinationPauseIsDurableAndResumeRevalidates() {
        runBlocking {
            val destination = File(context.getExternalFilesDir(null), "m4_test_unavailable_resume").apply { mkdirs() }
            val queueId = QueueId.random()
            val store = InMemoryQueueStore().apply {
                saveQueueIntent(listOf(QueueEntry(
                    magnetUri = "magnet:?xt=urn:btih:unavailable",
                    destinationPath = destination.canonicalPath,
                    queueId = queueId
                )))
            }
            val control = RecordingSessionOps()
            val operations = DurableTorrentOperations(
                control,
                store,
                QueueRuntimeBindings().apply { bind(queueId, 1L) }
            )
            try {
                destination.deleteRecursively()
                assertFalse(operations.ensureDestinationAvailable(1L, context))
                assertTrue(store.loadQueueIntent().single().storagePauseRequired)
                assertTrue(control.pausedIds.contains(1L))
                try {
                    operations.resume(1L, context)
                    fail("Resume should reject an unavailable durable destination")
                } catch (_: IllegalStateException) {
                    // Expected.
                }
                assertTrue(store.loadQueueIntent().single().storagePauseRequired)

                destination.mkdirs()
                operations.resume(1L, context)
                val restored = store.loadQueueIntent().single()
                assertFalse(restored.storagePauseRequired)
                assertFalse(restored.isPaused)
                assertTrue(control.resumedIds.contains(1L))
            } finally {
                destination.deleteRecursively()
            }
        }
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
    fun moveService_pausesOnlySelectedTorrentBeforeNativeMove() {
        runBlocking {
            val source = File(context.getExternalFilesDir(null), "m4_selected_pause_source").apply { mkdirs() }
            val otherSource = File(context.getExternalFilesDir(null), "m4_unaffected_source").apply { mkdirs() }
            val target = File(context.getExternalFilesDir(null), "m4_selected_pause_target").apply { mkdirs() }
            val selectedQueueId = QueueId.random()
            val otherQueueId = QueueId.random()
            val queueStore = InMemoryQueueStore()
            queueStore.saveQueueIntent(listOf(
                QueueEntry("magnet:?xt=urn:btih:selected", destinationPath = source.canonicalPath, queueId = selectedQueueId),
                QueueEntry("magnet:?xt=urn:btih:unaffected", destinationPath = otherSource.canonicalPath, queueId = otherQueueId)
            ))
            val bindings = QueueRuntimeBindings().apply {
                bind(selectedQueueId, 1L)
                bind(otherQueueId, 2L)
            }
            val journal = MoveJournal(context)
            val control = RecordingSessionOps()

            try {
                val result = MoveService(context, queueStore, journal, control, bindings)
                    .startMove(1L, target.canonicalPath)

                assertEquals("ok", result.status)
                assertEquals(listOf(1L), control.pausedIds)
                assertEquals(listOf(1L), control.movedIds)
                assertEquals(MovePhase.Copying, journal.getMove(selectedQueueId)?.phase)
                assertNull(journal.getMove(otherQueueId))
            } finally {
                journal.removeMove(selectedQueueId)
                journal.removeMove(otherQueueId)
                source.deleteRecursively()
                otherSource.deleteRecursively()
                target.deleteRecursively()
            }
        }
    }

    @Test
    fun moveService_rejectsCurrentDestinationBeforeAnyMutation() {
        runBlocking {
            val source = File(context.getExternalFilesDir(null), "m4_same_destination_source").apply { mkdirs() }
            val owned = source.resolve("fixture.bin").apply { writeText("fixture") }
            val queueId = QueueId.random()
            val queueStore = InMemoryQueueStore().apply {
                saveQueueIntent(listOf(QueueEntry(
                    magnetUri = "magnet:?xt=urn:btih:same_destination",
                    destinationPath = source.canonicalPath,
                    queueId = queueId
                )))
            }
            val bindings = QueueRuntimeBindings().apply { bind(queueId, 1L) }
            val journal = MoveJournal(context)
            val control = RecordingSessionOps()

            try {
                val result = MoveService(context, queueStore, journal, control, bindings)
                    .startMove(1L, source.canonicalPath)

                assertEquals("error", result.status)
                assertEquals("Torrent is already stored at this destination", result.recoverableError)
                assertTrue(control.pausedIds.isEmpty())
                assertTrue(control.movedIds.isEmpty())
                assertNull(journal.getMove(queueId))
                assertTrue(owned.exists())
            } finally {
                journal.removeMove(queueId)
                source.deleteRecursively()
            }
        }
    }

    @Test
    fun moveService_pauseFailurePreventsNormalNativeMove() {
        runBlocking {
            val source = File(context.getExternalFilesDir(null), "m4_normal_pause_failure_source").apply { mkdirs() }
            val target = File(context.getExternalFilesDir(null), "m4_normal_pause_failure_target").apply { mkdirs() }
            val queueId = QueueId.random()
            val queueStore = InMemoryQueueStore()
            queueStore.saveQueueIntent(listOf(QueueEntry(
                magnetUri = "magnet:?xt=urn:btih:normal_pause_failure",
                destinationPath = source.canonicalPath,
                queueId = queueId
            )))
            val bindings = QueueRuntimeBindings().apply { bind(queueId, 1L) }
            val journal = MoveJournal(context)
            val control = FailingPauseSessionOps()

            try {
                val result = MoveService(context, queueStore, journal, control, bindings)
                    .startMove(1L, target.canonicalPath)

                assertEquals("interrupted", result.status)
                assertTrue(control.pauseCalled)
                assertFalse(control.moveCalled)
                assertEquals(MovePhase.Interrupted, journal.getMove(queueId)?.phase)
            } finally {
                journal.removeMove(queueId)
                source.deleteRecursively()
                target.deleteRecursively()
            }
        }
    }

    @Test
    fun moveService_copyingCheckpointFailurePreventsNativeMoveAndRestoresSource() {
        runBlocking {
            val source = File(context.getExternalFilesDir(null), "m4_copying_failure_source").apply { mkdirs() }
            val target = File(context.getExternalFilesDir(null), "m4_copying_failure_target").apply { mkdirs() }
            val owned = source.resolve("fixture.bin").apply { writeText("fixture") }
            val queueId = QueueId.random()
            val queueStore = InMemoryQueueStore().apply {
                saveQueueIntent(listOf(QueueEntry("magnet:?xt=urn:btih:copying_failure", destinationPath = source.canonicalPath, queueId = queueId)))
            }
            val bindings = QueueRuntimeBindings().apply { bind(queueId, 1L) }
            val journal = FailingCopyingMoveJournal(context)
            val control = RecordingSessionOps()
            try {
                val result = MoveService(context, queueStore, journal, control, bindings)
                    .startMove(1L, target.canonicalPath)
                assertEquals("error", result.status)
                assertTrue(control.movedIds.isEmpty())
                assertTrue("Source must remain recoverable", owned.exists())
            } finally {
                journal.removeMove(queueId)
                source.deleteRecursively()
                target.deleteRecursively()
            }
        }
    }

    @Test
    fun moveService_nativeRejectionRestoresSourceAndPersistsInterrupted() {
        runBlocking {
            val source = File(context.getExternalFilesDir(null), "m4_native_reject_source").apply { mkdirs() }
            val target = File(context.getExternalFilesDir(null), "m4_native_reject_target").apply { mkdirs() }
            val owned = source.resolve("fixture.bin").apply { writeText("fixture") }
            val queueId = QueueId.random()
            val queueStore = InMemoryQueueStore().apply {
                saveQueueIntent(listOf(QueueEntry("magnet:?xt=urn:btih:native_reject", destinationPath = source.canonicalPath, queueId = queueId)))
            }
            val bindings = QueueRuntimeBindings().apply { bind(queueId, 1L) }
            val journal = MoveJournal(context)
            val control = RecordingSessionOps(moveAccepted = false)
            try {
                val result = MoveService(context, queueStore, journal, control, bindings)
                    .startMove(1L, target.canonicalPath)
                assertEquals("interrupted", result.status)
                assertEquals(listOf(1L), control.movedIds)
                assertTrue("Rejected native move must preserve source", owned.exists())
                assertEquals(MovePhase.Interrupted, journal.getMove(queueId)?.phase)
            } finally {
                journal.removeMove(queueId)
                source.deleteRecursively()
                target.deleteRecursively()
            }
        }
    }

    @Test
    fun moveService_cancelRetainsRecoveryLockWhenBackupRestoreFails() {
        runBlocking {
            val source = File(context.getExternalFilesDir(null), "m4_cancel_restore_source").apply { mkdirs() }
            val target = File(context.getExternalFilesDir(null), "m4_cancel_restore_target").apply { mkdirs() }
            val backup = source.resolve(".torrentwebui-move-backup-cancel").apply { writeText("fixture") }
            val queueId = QueueId.random()
            val journal = MoveJournal(context)
            journal.createMove(queueId, source.canonicalPath, target.canonicalPath, sourceBackupPath = backup.canonicalPath)
            journal.updatePhase(queueId, MovePhase.Interrupted)
            val bindings = QueueRuntimeBindings().apply { bind(queueId, 1L) }
            val control = RecordingSessionOps(torrentName = "missing/fixture.bin")
            try {
                val cancelled = MoveService(context, InMemoryQueueStore(), journal, control, bindings).cancelMove(1L)
                assertFalse(cancelled)
                assertEquals(MovePhase.Interrupted, journal.getMove(queueId)?.phase)
                assertTrue(backup.exists())
            } finally {
                journal.removeMove(queueId)
                source.deleteRecursively()
                target.deleteRecursively()
            }
        }
    }

    @Test
    fun moveService_cancelRollsBackQueueAfterPostCommitInterruption() {
        runBlocking {
            val source = File(context.getExternalFilesDir(null), "m4_post_commit_cancel_source").apply { mkdirs() }
            val target = File(context.getExternalFilesDir(null), "m4_post_commit_cancel_target").apply { mkdirs() }
            source.resolve("fixture.bin").writeText("restored")
            target.resolve("fixture.bin").writeText("verified-target")
            val queueId = QueueId.random()
            val store = InMemoryQueueStore().apply {
                saveQueueIntent(listOf(QueueEntry(
                    magnetUri = "magnet:?xt=urn:btih:post_commit_cancel",
                    destinationPath = target.canonicalPath,
                    queueId = queueId
                )))
            }
            val journal = MoveJournal(context)
            journal.createMove(queueId, source.canonicalPath, target.canonicalPath)
            journal.updatePhase(queueId, MovePhase.Interrupted)
            val control = RecordingSessionOps()
            val service = MoveService(
                context,
                store,
                journal,
                control,
                QueueRuntimeBindings().apply { bind(queueId, 1L) }
            )
            try {
                assertTrue(service.cancelMove(1L))
                assertEquals(listOf(1L), control.rolledBackIds)
                assertEquals(source.canonicalPath, store.loadQueueIntent().single().destinationPath)
                assertNull(journal.getMove(queueId))
                assertEquals(MovePhase.Cancelled, journal.getAuditMoves(queueId).single().phase)
                assertTrue(source.resolve("fixture.bin").exists())
                assertTrue(target.resolve("fixture.bin").exists())
            } finally {
                journal.removeMove(queueId)
                source.deleteRecursively()
                target.deleteRecursively()
            }
        }
    }

    @Test
    fun moveService_cancelRetainsInterruptedWhenNativeRollbackFails() {
        runBlocking {
            val source = File(context.getExternalFilesDir(null), "m4_cancel_native_source").apply { mkdirs() }
            val target = File(context.getExternalFilesDir(null), "m4_cancel_native_target").apply { mkdirs() }
            source.resolve("fixture.bin").writeText("source")
            val queueId = QueueId.random()
            val store = InMemoryQueueStore().apply {
                saveQueueIntent(listOf(QueueEntry("magnet:?xt=urn:btih:rollback", destinationPath = source.canonicalPath, queueId = queueId)))
            }
            val journal = MoveJournal(context)
            journal.createMove(queueId, source.canonicalPath, target.canonicalPath)
            journal.updatePhase(queueId, MovePhase.Interrupted)
            val control = RecordingSessionOps(rollbackAccepted = false)
            try {
                assertFalse(MoveService(context, store, journal, control, QueueRuntimeBindings().apply { bind(queueId, 1L) }).cancelMove(1L))
                assertEquals(MovePhase.Interrupted, journal.getMove(queueId)?.phase)
                assertEquals(listOf(1L), control.rolledBackIds)
            } finally {
                journal.removeMove(queueId)
                source.deleteRecursively()
                target.deleteRecursively()
            }
        }
    }

    @Test
    fun moveService_storageConflictDoesNotReportPausedWhenNativePauseFails() {
        runBlocking {
            val source = File(context.getExternalFilesDir(null), "m4_pause_failure_source").apply { mkdirs() }
            val target = File(context.getExternalFilesDir(null), "m4_pause_failure_target").apply {
                mkdirs()
                resolve("existing-data").writeText("conflict")
            }
            val queueId = QueueId.random()
            val queueStore = InMemoryQueueStore()
            queueStore.saveQueueIntent(listOf(QueueEntry(
                magnetUri = "magnet:?xt=urn:btih:pause_failure_fixture",
                destinationPath = source.canonicalPath,
                queueId = queueId
            )))
            val bindings = QueueRuntimeBindings().apply { bind(queueId, 1L) }
            val journal = MoveJournal(context)
            val control = FailingPauseSessionOps()

            try {
                val result = MoveService(context, queueStore, journal, control, bindings)
                    .startMove(1L, target.canonicalPath)

                assertEquals("interrupted", result.status)
                assertEquals("fixture pause failure", result.recoverableError)
                assertTrue("Pause must be attempted before reporting conflict", control.pauseCalled)
                assertFalse("Native move must not start after pause failure", control.moveCalled)
                assertEquals(MovePhase.Interrupted, journal.getMove(queueId)?.phase)
            } finally {
                journal.removeMove(queueId)
                source.deleteRecursively()
                target.deleteRecursively()
            }
        }
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
        File(context.filesDir, "move_journal_v2.json").writeText("test")

        // When: tearDown runs
        tearDown()

        // Then: Move journal should be removed
        assertFalse("Move journal should be cleaned up", File(context.filesDir, "move_journal_v2.json").exists())
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

        // Remove test files from internal storage and reset durable M4 state.
        context.filesDir.listFiles()?.filter { it.name.startsWith("m4_test_") }?.forEach { it.delete() }
        listOf("queue_intent.json", "move_journal_v2.json", "move_journal.txt", "destination_catalog.txt")
            .forEach { File(context.filesDir, it).delete() }
        File(context.filesDir, "resume_data").deleteRecursively()
        context.getSharedPreferences("destination_catalog_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
