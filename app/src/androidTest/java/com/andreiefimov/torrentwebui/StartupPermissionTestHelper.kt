package com.andreiefimov.torrentwebui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.ContextCompat

/** Verifies the framework permission state established through visible Android UI. */
internal object StartupPermissionTestHelper {

    fun ensureGranted(context: Context) {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        ) { "POST_NOTIFICATIONS was not granted; daemon acceptance setup cannot continue" }
        check(Environment.isExternalStorageManager()) {
            "All Files Access was not granted through Android Settings; host acceptance setup must establish it visibly"
        }
    }
}
