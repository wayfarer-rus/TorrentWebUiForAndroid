package com.andreiefimov.torrentwebui

import android.Manifest
import android.app.ActivityManager
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

    /** Excludes the host's recent instrumentation restart from product force-stop semantics. */
    fun clearInstrumentationStop(context: Context) {
        ForceStopDetector.lastUserRequestedExitTimestamp(context)?.let { timestamp ->
            if (!RecoverySuppressionStore.hasHandledForceStopExit(context, timestamp)) {
                val ageMs = System.currentTimeMillis() - timestamp
                check(ageMs in 0L..60_000L) {
                    "Refusing to clear a user-requested exit that predates instrumentation setup"
                }
                check(RecoverySuppressionStore.markDetectedForceStop(context, timestamp)) {
                    "Instrumentation force-stop timestamp could not be acknowledged"
                }
            }
        }
        check(RecoverySuppressionStore.clearForceStopped(context)) {
            "Instrumentation force-stop suppression could not be cleared"
        }
    }

    fun stopDaemonIfActive(context: Context, timeoutMs: Long = 10_000L) {
        if (TorrentDaemon.getHealthStatus(context).lifecycleState !=
            TorrentDaemon.DaemonState.Stopped.name
        ) {
            TorrentDaemon.stop(context)
        }
        awaitDaemonStopped(context, timeoutMs)
    }

    fun awaitDaemonStopped(context: Context, timeoutMs: Long = 10_000L) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (TorrentDaemon.getHealthStatus(context).lifecycleState ==
                TorrentDaemon.DaemonState.Stopped.name &&
                !isDaemonServiceActive(context)
            ) return
            Thread.sleep(50)
        }
        error("Timed out waiting for daemon service destruction during acceptance cleanup")
    }

    @Suppress("DEPRECATION")
    fun isDaemonServiceActive(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == TorrentDaemon::class.java.name }
}
