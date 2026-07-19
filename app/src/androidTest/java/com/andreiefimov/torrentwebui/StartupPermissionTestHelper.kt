package com.andreiefimov.torrentwebui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/** Establishes the real framework permission state required by daemon acceptance tests. */
internal object StartupPermissionTestHelper {

    fun ensureGranted(context: Context) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = context.packageName

        runShellCommand("pm grant $packageName ${Manifest.permission.POST_NOTIFICATIONS}")
        runShellCommand("appops set $packageName MANAGE_EXTERNAL_STORAGE allow")
        instrumentation.waitForIdleSync()

        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        ) { "POST_NOTIFICATIONS was not granted; daemon acceptance setup cannot continue" }
        check(Environment.isExternalStorageManager()) {
            "All Files Access was not granted; daemon acceptance setup cannot continue"
        }
    }

    private fun runShellCommand(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
    }
}
