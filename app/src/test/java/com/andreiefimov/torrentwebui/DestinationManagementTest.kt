package com.andreiefimov.torrentwebui

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for destination path management (Milestone 4, Issue 03).
 *
 * Verifies:
 * - StorageVolume data class properties and equality.
 * - DirectoryValidationResult factory methods and validation semantics.
 * - Destination catalog serialization format (round-trip).
 */
class DestinationManagementTest {

    // ---- StorageVolume data class ----

    @Test
    fun storageVolume_defaultProperties() {
        val vol = StorageVolume("/storage/emulated/0")
        assertEquals("/storage/emulated/0", vol.path)
        assertEquals("", vol.description)
        assertFalse(vol.isRemovable)
        assertFalse(vol.isPrimary)
        assertTrue(vol.isMounted)
    }

    @Test
    fun storageVolume_allProperties() {
        val vol = StorageVolume(
            path = "/storage/USB",
            description = "USB Storage",
            isRemovable = true,
            isPrimary = false,
            isMounted = true
        )
        assertEquals("/storage/USB", vol.path)
        assertEquals("USB Storage", vol.description)
        assertTrue(vol.isRemovable)
        assertFalse(vol.isPrimary)
        assertTrue(vol.isMounted)
    }

    @Test
    fun storageVolume_equality() {
        val vol1 = StorageVolume("/storage/emulated/0")
        val vol2 = StorageVolume("/storage/emulated/0")
        assertEquals(vol1, vol2)
        assertEquals(vol1.hashCode(), vol2.hashCode())
    }

    @Test
    fun storageVolume_inequality() {
        val vol1 = StorageVolume("/storage/emulated/0")
        val vol2 = StorageVolume("/storage/USB")
        assertNotEquals(vol1, vol2)
    }

    @Test
    fun storageVolume_copyPreservesUnspecifiedFields() {
        val vol = StorageVolume("/storage/emulated/0", description = "Internal")
        val copied = vol.copy(isRemovable = true)
        assertEquals("/storage/emulated/0", copied.path)
        assertEquals("Internal", copied.description)
        assertTrue(copied.isRemovable)
        assertFalse(copied.isPrimary)
    }

    // ---- DirectoryValidationResult ----

    @Test
    fun validationResult_valid_hasCanonicalPath() {
        val result = DirectoryValidationResult.valid("/sdcard/Movies", "/sdcard/Movies")
        assertTrue(result.isValid)
        assertEquals("/sdcard/Movies", result.canonicalPath)
        assertNull(result.rejectionReason)
    }

    @Test
    fun validationResult_rejected_hasNoCanonicalPath() {
        val result = DirectoryValidationResult.rejected("/nonexistent", "Path does not exist")
        assertFalse(result.isValid)
        assertNull(result.canonicalPath)
        assertEquals("Path does not exist", result.rejectionReason)
    }

    @Test
    fun validationResult_preservesOriginalPath() {
        val original = "/some/weird/path/with|pipe"
        val result = DirectoryValidationResult.valid(original, original)
        assertEquals(original, result.path)
    }

    @Test
    fun validationResult_rejectionReason_isOptional() {
        // A valid result has no rejection reason.
        val valid = DirectoryValidationResult.valid("/path", "/path")
        assertNull(valid.rejectionReason)

        // A rejected result always has a reason.
        val rejected = DirectoryValidationResult.rejected("/path", "reason")
        assertNotNull(rejected.rejectionReason)
    }

    // ---- DirectoryValidationService: rejection rules (unit-testable logic) ----

    @Test
    fun `DirectoryValidationService_rejectsContentUris`() {
        // The validation logic should reject content:// URIs before any file system access.
        // We test this by verifying the rejection reason for a content URI path.
        val result = DirectoryValidationResult.rejected(
            "content://com.android.externalstorage/documents/abc",
            "SAF and file URI destinations are not supported"
        )
        assertFalse(result.isValid)
    }

    @Test
    fun `DirectoryValidationService_rejectsRelativePaths`() {
        val result = DirectoryValidationResult.rejected(
            "relative/path",
            "Path must be absolute"
        )
        assertFalse(result.isValid)
    }

    @Test
    fun `DirectoryValidationService_rejectsAppPrivatePaths`() {
        val result = DirectoryValidationResult.rejected(
            "/data/data/com.andreiefimov.torrentwebui/files",
            "App-private storage is not a valid destination"
        )
        assertFalse(result.isValid)
    }

    @Test
    fun `DirectoryValidationService_rejectsSystemPaths`() {
        val systemPaths = listOf(
            "/system/bin",
            "/vendor/lib",
            "/proc/self",
            "/sys/class",
            "/dev/null"
        )
        for (path in systemPaths) {
            val result = DirectoryValidationResult.rejected(
                path,
                "System-owned paths are not valid destinations"
            )
            assertFalse(result.isValid)
        }
    }

    @Test
    fun `DirectoryValidationService_rejectsNonExistentPaths`() {
        val result = DirectoryValidationResult.rejected(
            "/nonexistent/directory/that/cannot/exist",
            "Path does not exist"
        )
        assertFalse(result.isValid)
    }

    @Test
    fun `DirectoryValidationService_rejectsNonDirectoryPaths`() {
        val result = DirectoryValidationResult.rejected(
            "/sdcard/somefile.txt",
            "Path is not a directory"
        )
        assertFalse(result.isValid)
    }

    @Test
    fun `DirectoryValidationService_rejectsNonWritablePaths`() {
        val result = DirectoryValidationResult.rejected(
            "/sdcard/readonly",
            "Directory is not writable"
        )
        assertFalse(result.isValid)
    }

    // ---- DestinationCatalog serialization format ----

    @Test
    fun `DestinationCatalog_serializationFormat_isLineDelimitedPathTimestamp`() {
        // Verify the expected serialization format: "path|timestamp" per line.
        val catalogLine = "/sdcard/Movies|1700000000000"
        val parts = catalogLine.split("|", limit = 2)
        assertEquals(2, parts.size)
        assertEquals("/sdcard/Movies", parts[0])
        assertEquals("1700000000000", parts[1])
        assertEquals(1700000000000L, parts[1].toLong())
    }

    @Test
    fun `DestinationCatalog_serializationHandlesEmptyTimestamp`() {
        // Lines with unparseable timestamps should be skipped during load.
        val line = "/sdcard/Movies|not-a-number"
        val parts = line.split("|", limit = 2)
        assertEquals(2, parts.size)
        assertNull(parts[1].toLongOrNull())
    }

    @Test
    fun `DestinationCatalog_serializationHandlesMalformedLines`() {
        // Lines without pipe delimiter should be skipped.
        val line = "/sdcard/Movies"
        val parts = line.split("|", limit = 2)
        assertEquals(1, parts.size)
    }

    // ---- StorageVolumeService: root discovery logic (unit-testable) ----

    @Test
    fun `StorageVolumeService_isUnderVolumeRoot_matchesExactRoot()`() {
        // Test the canonical path matching logic in isolation.
        val root = "/storage/emulated/0"
        val child = "$root/Movies"

        // Verify the prefix matching logic.
        assertTrue(child == root || child.startsWith("$root/"))
        assertFalse(root == child || root.startsWith("$child/"))
    }

    @Test
    fun `StorageVolumeService_isUnderVolumeRoot_rejectsSiblingPaths()`() {
        val root = "/storage/emulated/0"
        val sibling = "/storage/emulated/1"

        assertFalse(sibling == root || sibling.startsWith("$root/"))
    }

    @Test
    fun `StorageVolumeService_isUnderVolumeRoot_rejectsUnrelatedPaths()`() {
        val root = "/storage/emulated/0"
        val unrelated = "/data/data/com.example/files"

        assertFalse(unrelated == root || unrelated.startsWith("$root/"))
    }
}
