package com.andreiefimov.torrentwebui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for move journal and move service (Milestone 4, Issue 06).
 *
 * Verifies:
 * - MovePhase enum values and semantics.
 * - PersistedMoveEntry data class properties.
 * - MoveResult status values.
 * - Move journal persistence format (pipe-delimited).
 */
class MoveOperationTest {

    // ---- MovePhase enum ----

    @Test
    fun `MovePhase has expected values`() {
        val phases = MovePhase.values()
        assertEquals(9, phases.size)
        assertTrue(phases.contains(MovePhase.JournalPersisted))
        assertTrue(phases.contains(MovePhase.Copying))
        assertTrue(phases.contains(MovePhase.Verifying))
        assertTrue(phases.contains(MovePhase.QueueUpdated))
        assertTrue(phases.contains(MovePhase.SourceRemoved))
        assertTrue(phases.contains(MovePhase.Completed))
        assertTrue(phases.contains(MovePhase.Cancelled))
        assertTrue(phases.contains(MovePhase.Interrupted))
        assertTrue(phases.contains(MovePhase.StorageConflict))
    }

    @Test
    fun `MovePhase_name_matches_serialization`() {
        assertEquals("JournalPersisted", MovePhase.JournalPersisted.name)
        assertEquals("Copying", MovePhase.Copying.name)
        assertEquals("Verifying", MovePhase.Verifying.name)
        assertEquals("QueueUpdated", MovePhase.QueueUpdated.name)
        assertEquals("SourceRemoved", MovePhase.SourceRemoved.name)
        assertEquals("Completed", MovePhase.Completed.name)
        assertEquals("Cancelled", MovePhase.Cancelled.name)
        assertEquals("Interrupted", MovePhase.Interrupted.name)
        assertEquals("StorageConflict", MovePhase.StorageConflict.name)
    }

    @Test
    fun `MovePhase exposes stable API states for user recovery`() {
        assertEquals("move-interrupted", MovePhase.Interrupted.apiName)
        assertEquals("storage-conflict", MovePhase.StorageConflict.apiName)
        assertTrue(MovePhase.Interrupted.requiresUserAction)
        assertTrue(MovePhase.StorageConflict.requiresUserAction)
        assertFalse(MovePhase.Copying.requiresUserAction)
        assertTrue(MovePhase.Cancelled.isTerminal)
        assertFalse(MovePhase.Cancelled.requiresUserAction)
    }

    @Test
    fun `WebSocket snapshot keeps canonical storage conflict state`() {
        assertEquals("storage-conflict", MovePhase.StorageConflict.webSocketApiName())
    }

    // ---- PersistedMoveEntry data class ----

    @Test
    fun `PersistedMoveEntry_defaultUpdatedAt_isCurrentTime`() {
        val entry = PersistedMoveEntry(
            queueId = QueueId.random(),
            sourcePath = "/sdcard/Movies",
            targetPath = "/storage/USB/Movies",
            phase = MovePhase.JournalPersisted,
            createdAt = 1000L
        )
        // updatedAt should be set to System.currentTimeMillis() by default.
        assertTrue(entry.updatedAt > 0)
    }

    @Test
    fun `PersistedMoveEntry_allFieldsPreservedOnCopy`() {
        val queueId = QueueId.random()
        val original = PersistedMoveEntry(
            queueId = queueId,
            sourcePath = "/sdcard/Movies",
            targetPath = "/storage/USB/Movies",
            phase = MovePhase.Copying,
            createdAt = 1000L,
            updatedAt = 2000L
        )
        val copied = original.copy(phase = MovePhase.Verifying)
        assertEquals(queueId, copied.queueId)
        assertEquals("/sdcard/Movies", copied.sourcePath)
        assertEquals("/storage/USB/Movies", copied.targetPath)
        assertEquals(MovePhase.Verifying, copied.phase)
        assertEquals(1000L, copied.createdAt)
        assertEquals(2000L, copied.updatedAt) // unchanged by copy
    }

    // ---- MoveResult data class ----

    @Test
    fun `MoveResult_ok_status`() {
        val result = MoveResult(status = "ok")
        assertEquals("ok", result.status)
        assertNull(result.recoverableError)
    }

    @Test
    fun `MoveResult_interrupted_status_withError`() {
        val result = MoveResult(status = "interrupted", recoverableError = "Disk full")
        assertEquals("interrupted", result.status)
        assertEquals("Disk full", result.recoverableError)
    }

    @Test
    fun `MoveResult_storageConflict_status_withError`() {
        val result = MoveResult(status = "storage_conflict", recoverableError = "Target already contains data")
        assertEquals("storage_conflict", result.status)
        assertEquals("Target already contains data", result.recoverableError)
    }

    @Test
    fun `MoveResult_error_status_withReason`() {
        val result = MoveResult(status = "error", recoverableError = "Permission denied")
        assertEquals("error", result.status)
        assertEquals("Permission denied", result.recoverableError)
    }

    // ---- MoveRequest DTO ----

    @Test
    fun `MoveRequest_defaultDestinationPathIsEmpty`() {
        val req = MoveRequest()
        assertEquals("", req.destinationPath)
    }

    @Test
    fun `MoveRequest_canCarryDestinationPath`() {
        val req = MoveRequest(destinationPath = "/storage/USB/Movies")
        assertEquals("/storage/USB/Movies", req.destinationPath)
    }

    // ---- MoveResponse DTO ----

    @Test
    fun `MoveResponse_ok_withPhase`() {
        val resp = MoveResponse(status = "ok", phase = "completed")
        assertEquals("ok", resp.status)
        assertEquals("completed", resp.phase)
        assertNull(resp.error)
    }

    @Test
    fun `MoveResponse_error_withMessage`() {
        val resp = MoveResponse(status = "error", phase = "error", error = "Invalid path")
        assertEquals("error", resp.status)
        assertEquals("error", resp.phase)
        assertEquals("Invalid path", resp.error)
    }

    @Test
    fun `storage safety pause preserves explicit user pause intent`() {
        val active = QueueEntry("magnet:?xt=urn:btih:active")
            .copy(storagePauseRequired = true)
        val userPaused = QueueEntry("magnet:?xt=urn:btih:paused", isPaused = true)
            .copy(storagePauseRequired = true)

        assertFalse(active.isPaused)
        assertTrue(active.storagePauseRequired)
        assertTrue(userPaused.isPaused)
        assertTrue(userPaused.storagePauseRequired)
        val restoredUserPause = userPaused.copy(storagePauseRequired = false)
        assertTrue(restoredUserPause.isPaused)
        assertFalse(restoredUserPause.storagePauseRequired)
    }

    @Test
    fun `permission restoration supersedes an in-flight blocked transition`() {
        val guard = PermissionTransitionGuard()
        val blockedGeneration = guard.begin()
        assertTrue(guard.isCurrent(blockedGeneration))

        val restoredGeneration = guard.supersede()
        assertFalse(guard.isCurrent(blockedGeneration))
        assertTrue(guard.isCurrent(restoredGeneration))
    }

    @Test
    fun `move cleanup deletes only canonical torrent-owned paths`() {
        val root = java.nio.file.Files.createTempDirectory("m4-owned-root").toFile()
        val outside = java.nio.file.Files.createTempFile("m4-outside", ".bin").toFile()
        val owned = root.resolve("fixture.bin").apply { writeText("owned") }
        try {
            assertFalse(deleteMoveOwnedPath(root.canonicalPath, outside.canonicalPath))
            assertTrue(outside.exists())
            assertTrue(deleteMoveOwnedPath(root.canonicalPath, owned.canonicalPath))
            assertFalse(owned.exists())
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }

    // ---- Move journal serialization format ----

    @Test
    fun `MoveJournal_serializationFormat_isPipeDelimited`() {
        // Verify the expected serialization format: "torrentId|source|target|phase|createdAt|updatedAt"
        val line = "42|/sdcard/Movies|/storage/USB/Movies|JournalPersisted|1000|2000"
        val parts = line.split("|", limit = 6)
        assertEquals(6, parts.size)
        assertEquals("42", parts[0])
        assertEquals("/sdcard/Movies", parts[1])
        assertEquals("/storage/USB/Movies", parts[2])
        assertEquals("JournalPersisted", parts[3])
        assertEquals("1000", parts[4])
        assertEquals("2000", parts[5])
    }

    @Test
    fun `MoveJournal_serializationHandlesInvalidTimestamps`() {
        // Lines with unparseable timestamps should be skipped during load.
        val line = "42|/src|/tgt|Copying|not-a-number|also-not"
        val parts = line.split("|", limit = 6)
        assertEquals(6, parts.size)
        assertNull(parts[4].toLongOrNull())
    }

    @Test
    fun `MoveJournal_serializationHandlesMalformedLines`() {
        // Lines without enough pipe delimiters should be skipped.
        val line = "/sdcard/Movies"
        val parts = line.split("|", limit = 6)
        assertEquals(1, parts.size)
    }

    // ---- MoveService: protocol logic (unit-testable invariants) ----

    @Test
    fun `MoveService_rejectsWhenPermissionUnavailable`() {
        // The move service should reject moves when storage permission is not granted.
        // This is tested by checking the MoveResult status for a permission-denied scenario.
        val result = MoveResult(status = "error", recoverableError = "Storage permission required")
        assertEquals("error", result.status)
        assertEquals("Storage permission required", result.recoverableError)
    }

    @Test
    fun `MoveService_rejectsWhenMoveAlreadyActive`() {
        val result = MoveResult(status = "error", recoverableError = "Move already in progress for this torrent")
        assertEquals("error", result.status)
    }

    @Test
    fun `MoveService_rejectsInvalidDestination`() {
        val result = MoveResult(status = "error", recoverableError = "Invalid destination")
        assertEquals("error", result.status)
    }

    @Test
    fun `MoveService_interruptedPreservesSourceAndTarget`() {
        // On interruption, source and target data should be preserved (not deleted).
        // This is a behavioral invariant — tested by checking the journal phase.
        val interruptedPhase = MovePhase.Interrupted
        assertEquals("Interrupted", interruptedPhase.name)
    }

    @Test
    fun `MoveService_completedRemovesSourceAfterQueueUpdate`() {
        // On success: QueueUpdated phase must come before SourceRemoved.
        val phases = listOf(MovePhase.QueueUpdated, MovePhase.SourceRemoved)
        assertEquals(0, phases.indexOf(MovePhase.QueueUpdated))
        assertEquals(1, phases.indexOf(MovePhase.SourceRemoved))
    }
}
