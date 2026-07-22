package com.andreiefimov.torrentwebui

import android.content.Context

/** Persists only whether this install previously reached storage-ready operation. */
internal object StoragePermissionHistory {
    private const val PREFERENCES = "storage_permission_history"
    private const val KEY_READY = "ready_seen"

    fun markReady(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_READY, true)
            .apply()
    }

    fun hasBeenReady(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_READY, false)
}
