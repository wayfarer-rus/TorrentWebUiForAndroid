package com.andreiefimov.torrentwebui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the manifest prerequisite for the Android All Files Access settings flow. */
@RunWith(AndroidJUnit4::class)
class StartupPermissionIntegrationTest {

    @Test
    fun appDeclaresAllFilesAccessPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val requestedPermissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()

        assertTrue(
            "MANAGE_EXTERNAL_STORAGE must be declared before Android enables the All Files Access toggle",
            requestedPermissions.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
        )
    }
}
