package com.andreiefimov.torrentwebui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Represents the current storage-permission state for M4 path-based operations.
 *
 * - [Ready]: MANAGE_EXTERNAL_STORAGE is granted; storage operations (add/move/browse) are enabled.
 * - [DeniedAtStartup]: Permission was not granted at app startup; daemon blocks storage operations
 *   and the Android fallback UI presents retry guidance. WebUI is not started until granted.
 * - [RevokedRuntime]: Permission was revoked while the app was running; affected torrents pause,
 *   adds/moves are rejected, and the authenticated WebUI reports this state. Recovery requires
 *   explicit user action to re-grant and resume.
 */
enum class StoragePermissionState {
    Ready,
    DeniedAtStartup,
    RevokedRuntime
}

/**
 * Checks and monitors MANAGE_EXTERNAL_STORAGE (All Files Access) permission state.
 *
 * This is a pure Android-framework concern — it does not touch the native layer or queue.
 * It provides:
 * - A one-shot check at startup ([checkAtStartup]) that returns [StoragePermissionState].
 * - A runtime poller ([getCurrentState]) for the WebUI and daemon to query.
 * - An intent builder ([launchPermissionSettings]) that opens system settings for the user
 *   to grant All Files Access (the only way to request MANAGE_EXTERNAL_STORAGE on Android 10+).
 *
 * Paths never appear in routine logs, notifications, or error text — only in authenticated
 * WebUI/API responses and explicit diagnostics.
 */
object StoragePermissionChecker {

    private const val TAG = "StoragePermChecker"

    /** The permission string checked by this module. */
    const val MANAGE_EXTERNAL_STORAGE_PERMISSION = Manifest.permission.MANAGE_EXTERNAL_STORAGE

    /** Returns the current storage permission state for the given context. */
    fun getCurrentState(context: Context): StoragePermissionState {
        val granted = isGranted(context)
        return if (granted) StoragePermissionState.Ready else StoragePermissionState.DeniedAtStartup
    }

    /** Returns true if MANAGE_EXTERNAL_STORAGE is currently granted. */
    fun isGranted(context: Context): Boolean {
        return try {
            android.os.Environment.isExternalStorageManager()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns an intent that opens the system All Files Access settings page,
     * where the user can grant MANAGE_EXTERNAL_STORAGE.
     */
    fun launchPermissionSettings(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }
}

    