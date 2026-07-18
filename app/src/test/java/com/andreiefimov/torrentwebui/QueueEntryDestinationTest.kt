package com.andreiefimov.torrentwebui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for per-torrent storage expansion and legacy path import (Milestone 4, Issue 01).
 *
 * Verifies:
 * - QueueEntry carries an optional destinationPath.
 * - InMemoryQueueStore migrateLegacyEntries assigns the global save path atomically.
 * - allEntriesHaveDestination correctly reflects queue state.
 * - isLegacySavePath utility rejects legacy paths and accepts approved ones.
 */
class QueueEntryDestinationTest {

    private val legacyPath = "/data/user/0/com.andreiefimov.torrentwebui/downloads"

    // ---- QueueEntry model ----

    @Test
    fun queueEntry_defaultDestinationPathIsNull() {
        val entry = QueueEntry(magnetUri = "magnet:?xt=urn:btih:test")
        assertNull(entry.destinationPath)
    }

    @Test
    fun queueEntry_canCarryDestinationPath() {
        val entry = QueueEntry(
            magnetUri = "magnet:?xt=urn:btih:test",
            destinationPath = "/sdcard/Movies"
        )
        assertEquals("/sdcard/Movies", entry.destinationPath)
    }

    @Test
    fun queueEntry_allFieldsPreservedOnCopy() {
        val original = QueueEntry(
            magnetUri = "magnet:?xt=urn:btih:test",
            isPaused = true,
            destinationPath = "/sdcard/Movies"
        )
        val copy = original.copy(isPaused = false)
        assertEquals("magnet:?xt=urn:btih:test", copy.magnetUri)
        assertFalse(copy.isPaused)
        assertEquals("/sdcard/Movies", copy.destinationPath)
    }

    @Test
    fun queueEntry_destinationPath_canBeExplicitlyNull() {
        val entry = QueueEntry(
            magnetUri = "magnet:test",
            destinationPath = null
        )
        assertNull(entry.destinationPath)
    }

    // ---- InMemoryQueueStore: legacy migration ----

    @Test
    fun `InMemory - migrateLegacyEntries migrates null destinations to global path`() = runTest {
        val store = InMemoryQueueStore(globalLegacySavePath = legacyPath)
        store.saveQueueIntent(
            listOf(
                QueueEntry("magnet:old1", destinationPath = null),
                QueueEntry("magnet:new1", destinationPath = "/sdcard/NewDest")
            )
        )

        val migrated = store.migrateLegacyEntries()

        assertTrue(migrated)
        val queue = store.loadQueueIntent()
        assertEquals(legacyPath, queue[0].destinationPath)
        assertEquals("/sdcard/NewDest", queue[1].destinationPath)
    }

    @Test
    fun `InMemory - migrateLegacyEntries returns false when no null destinations`() = runTest {
        val store = InMemoryQueueStore(globalLegacySavePath = legacyPath)
        store.saveQueueIntent(
            listOf(
                QueueEntry("magnet:1", destinationPath = "/sdcard/A"),
                QueueEntry("magnet:2", destinationPath = "/sdcard/B")
            )
        )

        val migrated = store.migrateLegacyEntries()

        assertFalse(migrated)
    }

    @Test
    fun `InMemory - migrateLegacyEntries returns false when no legacy path configured`() = runTest {
        val store = InMemoryQueueStore(globalLegacySavePath = null)
        store.saveQueueIntent(
            listOf(QueueEntry("magnet:1", destinationPath = null))
        )

        val migrated = store.migrateLegacyEntries()

        assertFalse(migrated)
    }

    @Test
    fun `InMemory - migrateLegacyEntries returns false for empty queue`() = runTest {
        val store = InMemoryQueueStore(globalLegacySavePath = legacyPath)

        val migrated = store.migrateLegacyEntries()

        assertFalse(migrated)
    }

    @Test
    fun `InMemory - migrateLegacyEntries preserves isPaused flag`() = runTest {
        val store = InMemoryQueueStore(globalLegacySavePath = legacyPath)
        store.saveQueueIntent(
            listOf(
                QueueEntry("magnet:1", isPaused = true, destinationPath = null),
                QueueEntry("magnet:2", isPaused = false, destinationPath = "/sdcard/X")
            )
        )

        store.migrateLegacyEntries()

        val queue = store.loadQueueIntent()
        assertTrue(queue[0].isPaused)
        assertFalse(queue[1].isPaused)
    }

    @Test
    fun `InMemory - migrateLegacyEntries preserves magnetUri exactly`() = runTest {
        val store = InMemoryQueueStore(globalLegacySavePath = legacyPath)
        val magnet = "magnet:?xt=urn:btih:a]1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b&dn=Test"
        store.saveQueueIntent(
            listOf(QueueEntry(magnet, destinationPath = null))
        )

        store.migrateLegacyEntries()

        val queue = store.loadQueueIntent()
        assertEquals(magnet, queue[0].magnetUri)
    }

    // ---- InMemoryQueueStore: allEntriesHaveDestination ----

    @Test
    fun `InMemory - allEntriesHaveDestination is false when any entry lacks path`() = runTest {
        val store = InMemoryQueueStore(globalLegacySavePath = legacyPath)
        store.saveQueueIntent(
            listOf(
                QueueEntry("magnet:1", destinationPath = null),
                QueueEntry("magnet:2", destinationPath = "/sdcard/A")
            )
        )

        assertFalse(store.allEntriesHaveDestination())
    }

    @Test
    fun `InMemory - allEntriesHaveDestination is true when all entries have paths`() = runTest {
        val store = InMemoryQueueStore(globalLegacySavePath = legacyPath)
        store.saveQueueIntent(
            listOf(
                QueueEntry("magnet:1", destinationPath = "/sdcard/A"),
                QueueEntry("magnet:2", destinationPath = "/sdcard/B")
            )
        )

        assertTrue(store.allEntriesHaveDestination())
    }

    @Test
    fun `InMemory - allEntriesHaveDestination is true for empty queue`() = runTest {
        val store = InMemoryQueueStore(globalLegacySavePath = legacyPath)
        assertTrue(store.allEntriesHaveDestination())
    }

    // ---- InMemoryQueueStore: resume data survives migration ----

    @Test
    fun `InMemory - resumeData survives legacy migration`() = runTest {
        val store = InMemoryQueueStore(globalLegacySavePath = legacyPath)
        store.saveQueueIntent(
            listOf(QueueEntry("magnet:1", destinationPath = null))
        )
        store.saveResumeData(42L, byteArrayOf(1, 2, 3))

        store.migrateLegacyEntries()

        val reloaded = store.loadQueueIntent()
        assertEquals(legacyPath, reloaded[0].destinationPath)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.loadResumeData(42L))
    }

    // ---- isLegacySavePath utility ----

    @Test
    fun `isLegacySavePath rejects the global legacy directory`() {
        assertTrue(isLegacySavePath(legacyPath, legacyPath))
    }

    @Test
    fun `isLegacySavePath accepts a non-legacy path`() {
        assertFalse(isLegacySavePath("/sdcard/Movies", legacyPath))
    }

    @Test
    fun `isLegacySavePath returns false when no legacy path configured`() {
        assertFalse(isLegacySavePath(legacyPath, null))
    }

    @Test
    fun `isLegacySavePath handles trailing slash equivalence`() {
        val legacyWithSlash = "$legacyPath/"
        assertTrue(isLegacySavePath(legacyWithSlash, legacyPath))
    }

    @Test
    fun `isLegacySavePath distinguishes sibling directories`() {
        assertFalse(isLegacySavePath("/data/user/0/com.andreiefimov.torrentwebui/other", legacyPath))
    }
}
