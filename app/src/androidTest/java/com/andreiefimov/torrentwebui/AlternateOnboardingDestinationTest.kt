package com.andreiefimov.torrentwebui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlternateOnboardingDestinationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var candidate: File

    @Before
    fun setUp() {
        File(context.filesDir, "destination_catalog.txt").delete()
        candidate = File(requireNotNull(context.getExternalFilesDir(null)), "m6_alternate_destination")
        candidate.mkdirs()
    }

    @After
    fun tearDown() {
        candidate.delete()
        File(context.filesDir, "destination_catalog.txt").delete()
    }

    @Test
    fun mountedVolumesValidationAndCanonicalApprovalUseSharedBackendRules() = runBlocking {
        val catalog = DestinationCatalog(context)
        val operations = AndroidStorageApiOperations(context) { catalog }
        val volumes = operations.volumes()

        assertTrue(volumes.any { it.isPrimary && it.path.startsWith("/") })
        assertTrue(volumes.all { it.isMounted && File(it.path).canRead() })

        val pasted = "${candidate.parentFile.canonicalPath}/./${candidate.name}"
        val validation = operations.validate(pasted)
        assertTrue(validation.isValid)
        assertEquals(candidate.canonicalPath, validation.canonicalPath)

        val approval = operations.approve(pasted)
        assertEquals(DestinationApprovalResult.Approved(candidate.canonicalPath), approval)
        assertTrue(catalog.listDestinations().contains(candidate.canonicalPath))
        assertEquals(candidate.canonicalPath, catalog.getLatestSelected())

        assertFalse(operations.validate("content://documents/tree/primary").isValid)
        assertTrue(
            operations.children("/storage/not-mounted-m6-test") is
                DirectoryChildrenResult.Unavailable
        )
    }

    @Test
    fun attachedRemovableVolumeExposesRealMountedPath() {
        val removable = AndroidStorageApiOperations(context) { DestinationCatalog(context) }
            .volumes()
            .filter { it.isRemovable }
        assumeTrue("No removable volume is attached to this test device", removable.isNotEmpty())
        assertTrue(removable.all { it.isMounted && it.path.startsWith("/") && File(it.path).canRead() })
    }
}
