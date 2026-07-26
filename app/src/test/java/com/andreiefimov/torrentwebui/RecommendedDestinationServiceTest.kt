package com.andreiefimov.torrentwebui

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedDestinationServiceTest {

    @Test
    fun `proposal uses primary volume canonical path without creating directories`() {
        val fs = FakeDestinationFileSystem(setOf("/storage/primary"))
        val service = service(fs)

        val result = service.proposal()

        assertEquals(
            RecommendedDestinationResult.Success("/storage/primary/Download/Torrents"),
            result
        )
        assertTrue(fs.created.isEmpty())
    }

    @Test
    fun `confirmation recursively creates validates and approves canonical destination`() = runTest {
        val fs = FakeDestinationFileSystem(setOf("/storage/primary"))
        var approved: String? = null
        val service = service(fs, approve = { approved = it; true })

        val result = service.confirm()

        assertEquals(
            RecommendedDestinationResult.Success("/storage/primary/Download/Torrents"),
            result
        )
        assertEquals(
            listOf("/storage/primary/Download", "/storage/primary/Download/Torrents"),
            fs.created
        )
        assertEquals("/storage/primary/Download/Torrents", approved)
    }

    @Test
    fun `existing recommended directory is reused without mutation`() = runTest {
        val fs = FakeDestinationFileSystem(
            setOf(
                "/storage/primary",
                "/storage/primary/Download",
                "/storage/primary/Download/Torrents"
            )
        )
        val service = service(fs)

        assertTrue(service.confirm() is RecommendedDestinationResult.Success)
        assertTrue(fs.created.isEmpty())
        assertTrue(fs.deleted.isEmpty())
    }

    @Test
    fun `canonicalization failure does not create or approve`() = runTest {
        val fs = FakeDestinationFileSystem(setOf("/storage/primary"))
        fs.canonicalFailure = "/storage/primary/Download/Torrents"
        var approvals = 0
        val service = service(fs, approve = { approvals += 1; true })

        val result = service.confirm()

        assertTrue(result is RecommendedDestinationResult.Failure)
        assertTrue(fs.created.isEmpty())
        assertEquals(0, approvals)
    }

    @Test
    fun `canonical path escaping primary volume is rejected before creation`() = runTest {
        val fs = FakeDestinationFileSystem(setOf("/storage/primary"))
        fs.canonicalOverrides["/storage/primary/Download/Torrents"] = "/outside/Torrents"
        val service = service(fs)

        val result = service.confirm()

        assertTrue(result is RecommendedDestinationResult.Failure)
        assertTrue(fs.created.isEmpty())
    }

    @Test
    fun `partial recursive creation failure removes only directories created by operation`() = runTest {
        val fs = FakeDestinationFileSystem(setOf("/storage/primary"))
        fs.createFailure = "/storage/primary/Download/Torrents"
        val service = service(fs)

        val result = service.confirm()

        assertTrue(result is RecommendedDestinationResult.Failure)
        assertEquals(listOf("/storage/primary/Download"), fs.deleted)
        assertTrue(fs.directories.contains("/storage/primary"))
    }

    @Test
    fun `validation failure rolls back newly created empty directories`() = runTest {
        val fs = FakeDestinationFileSystem(setOf("/storage/primary"))
        val service = service(
            fs,
            validate = { DirectoryValidationResult.rejected(it, "not writable") }
        )

        val result = service.confirm()

        assertTrue(result is RecommendedDestinationResult.Failure)
        assertEquals(
            listOf("/storage/primary/Download/Torrents", "/storage/primary/Download"),
            fs.deleted
        )
    }

    @Test
    fun `catalog persistence failure never claims approval and rolls back empty creation`() = runTest {
        val fs = FakeDestinationFileSystem(setOf("/storage/primary"))
        val service = service(fs, approve = { false })

        val result = service.confirm()

        assertTrue(result is RecommendedDestinationResult.Failure)
        assertEquals(
            listOf("/storage/primary/Download/Torrents", "/storage/primary/Download"),
            fs.deleted
        )
    }

    @Test
    fun `rollback retains newly created directory when it is no longer provably empty`() = runTest {
        val fs = FakeDestinationFileSystem(setOf("/storage/primary"))
        val target = "/storage/primary/Download/Torrents"
        val service = service(fs, approve = {
            fs.nonEmpty += target
            false
        })

        val result = service.confirm()

        assertTrue(result is RecommendedDestinationResult.Failure)
        assertTrue(fs.deleted.isEmpty())
        assertTrue(fs.directories.contains(target))
    }

    private fun service(
        fs: FakeDestinationFileSystem,
        validate: (String) -> DirectoryValidationResult = {
            DirectoryValidationResult.valid(it, it)
        },
        approve: suspend (String) -> Boolean = { true }
    ) = RecommendedDestinationService(
        volumes = {
            listOf(StorageVolume("/storage/removable", isRemovable = true)) +
                StorageVolume("/storage/primary", isPrimary = true)
        },
        fileSystem = fs,
        validate = validate,
        approve = approve
    )

    private class FakeDestinationFileSystem(
        initialDirectories: Set<String>
    ) : DestinationFileSystem {
        val directories = initialDirectories.toMutableSet()
        val created = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val nonEmpty = mutableSetOf<String>()
        var createFailure: String? = null
        var canonicalFailure: String? = null
        val canonicalOverrides = mutableMapOf<String, String>()

        override fun resolve(root: String, relative: String): String = "$root/$relative"

        override fun canonicalPath(path: String): String {
            if (path == canonicalFailure) throw IOException("canonical failure")
            return canonicalOverrides[path] ?: path.replace(Regex("/+"), "/")
        }

        override fun exists(path: String): Boolean = path in directories

        override fun isDirectory(path: String): Boolean = path in directories

        override fun createDirectory(path: String): Boolean {
            if (path == createFailure) return false
            directories += path
            created += path
            return true
        }

        override fun parent(path: String): String? =
            path.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { null }

        override fun isEmptyDirectory(path: String): Boolean =
            path in directories && path !in nonEmpty && directories.none {
                it != path && parent(it) == path
            }

        override fun deleteDirectory(path: String): Boolean {
            if (!isEmptyDirectory(path)) return false
            directories -= path
            deleted += path
            return true
        }
    }
}
