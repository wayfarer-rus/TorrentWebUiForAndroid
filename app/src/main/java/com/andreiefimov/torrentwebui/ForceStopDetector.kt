package com.andreiefimov.torrentwebui

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/** Reads the latest process exit reason when Android exposes it (API 30+). */
internal object ForceStopDetector {
    fun lastUserRequestedExitTimestamp(context: Context): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return try {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            activityManager
                .getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull { it.reason == ApplicationExitInfo.REASON_USER_REQUESTED }
                ?.timestamp
        } catch (e: SecurityException) {
            android.util.Log.w(TorrentDaemon.TAG, "Unable to read process exit reason")
            null
        }
    }
}
