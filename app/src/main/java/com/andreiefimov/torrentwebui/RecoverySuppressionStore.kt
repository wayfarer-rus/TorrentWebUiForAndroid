package com.andreiefimov.torrentwebui

import android.content.Context

/**
 * Stores whether an explicit force stop has suppressed automatic queue recovery.
 *
 * The marker is separate from queue records so a later user-initiated start can resume the
 * existing queue without treating an app launch as permission to do so.
 */
internal object RecoverySuppressionStore {
    private const val PREFERENCES_NAME = "daemon_recovery"
    private const val FORCE_STOP_KEY = "force_stop_suppresses_recovery"
    private const val LAST_HANDLED_FORCE_STOP_EXIT_TIMESTAMP_KEY = "last_handled_force_stop_exit_timestamp"

    fun markForceStopped(context: Context): Boolean =
        preferences(context).edit().putBoolean(FORCE_STOP_KEY, true).commit()

    fun hasHandledForceStopExit(context: Context, timestamp: Long): Boolean =
        preferences(context).getLong(LAST_HANDLED_FORCE_STOP_EXIT_TIMESTAMP_KEY, 0L) == timestamp

    /** Marks an Android-reported force stop once; repeated starts see the handled timestamp. */
    fun markDetectedForceStop(context: Context, timestamp: Long): Boolean =
        preferences(context).edit()
            .putBoolean(FORCE_STOP_KEY, true)
            .putLong(LAST_HANDLED_FORCE_STOP_EXIT_TIMESTAMP_KEY, timestamp)
            .commit()

    fun isForceStopped(context: Context): Boolean =
        preferences(context).getBoolean(FORCE_STOP_KEY, false)

    fun clearForceStopped(context: Context): Boolean =
        preferences(context).edit().remove(FORCE_STOP_KEY).commit()

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
