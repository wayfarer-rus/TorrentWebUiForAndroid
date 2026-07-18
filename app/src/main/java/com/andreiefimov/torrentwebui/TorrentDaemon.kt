package com.andreiefimov.torrentwebui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.os.Build
import android.os.IBinder
import android.app.Service.STOP_FOREGROUND_REMOVE
import androidx.core.app.NotificationCompat
import com.andreiefimov.torrentwebui.events.AlertDispatcher
import com.andreiefimov.torrentwebui.events.EventBus
import com.andreiefimov.torrentwebui.events.TorrentEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service that owns the native torrent session and WebUI server.
 *
 * The daemon survives MainActivity backgrounding and keeps the authenticated LAN WebUI available.
 * It stays active after the transfer queue becomes idle, so the LAN WebUI remains reachable;
 * only an explicit user **Stop downloads** action stops it.
 *
 * Notification permission (Android 13+) is required before the daemon can start as a foreground service.
 *
 * Lifecycle states:
 * - [Stopped]: No foreground service, native session, or WebUI server is running.
 * - [Starting]: Service is creating/restoring the session and starting the WebUI server.
 * - [Running]: Foreground service, native session, and WebUI server are live. Queue may be active or idle.
 * - [Stopping]: Explicit safe-stop checkpoint is being written.
 * - [RecoveryBlocked]: A record cannot safely resume (e.g., rejected resume data or unavailable storage).
 */
class TorrentDaemon : Service() {

    /** Represents the current daemon lifecycle state. */
    enum class DaemonState { Stopped, Starting, Running, Stopping, RecoveryBlocked }

    companion object {
        const val TAG = "TorrentDaemon"
        const val CHANNEL_ID = "torrent_daemon_channel"
        const val NOTIFICATION_ID = 1001

        /** Action to start the daemon. */
        const val ACTION_START = "com.andreiefimov.torrentwebui.START"

        /** Action to stop the daemon (from notification or external caller). */
        const val ACTION_STOP = "com.andreiefimov.torrentwebui.STOP"

        /** Action for a user request to resume after an explicit force stop. */
        const val ACTION_USER_START = "com.andreiefimov.torrentwebui.USER_START"

        /** Intent extra: whether this is a force stop (vs ordinary stop). */
        const val EXTRA_FORCE_STOP = "force_stop"

        /** Checkpoint interval for native resume data (30 seconds). */
        const val CHECKPOINT_INTERVAL_MS = 30_000L

        /** Safe-stop deadline (5 seconds). */
        const val SAFE_STOP_DEADLINE_MS = 5_000L

        /** Starts the daemon service with the given context. */
        fun start(context: Context) {
            val intent = Intent(context, TorrentDaemon::class.java).apply {
                action = ACTION_START
            }
            launchService(context, intent)
        }

        /** Stops the daemon service with an explicit user action. */
        fun stop(context: Context) {
            val intent = Intent(context, TorrentDaemon::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** Test helper that records force-stop intent before requesting service shutdown. */
        internal fun requestForceStopForTest(context: Context) {
            if (!RecoverySuppressionStore.markForceStopped(context)) {
                android.util.Log.e(TAG, "Unable to persist force-stop intent")
                return
            }
            val intent = Intent(context, TorrentDaemon::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_FORCE_STOP, true)
            }
            context.startService(intent)
        }

        /** Starts the daemon from an explicit user action, allowing suppressed recovery. */
        fun resume(context: Context) {
            val intent = Intent(context, TorrentDaemon::class.java).apply {
                action = ACTION_USER_START
            }
            launchService(context, intent)
        }

        private fun launchService(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Checks if the daemon is currently running. */
        fun isRunning(context: Context): Boolean {
            // This is a simplified check; in production, you'd track service state.
            return false
        }

        /** Returns the current daemon health status (non-sensitive). */
        fun getHealthStatus(context: Context): DaemonHealthStatus {
            // Query the actual daemon state from the static holder
            return currentDaemonState?.let { state ->
                DaemonHealthStatus(
                    lifecycleState = state.name,
                    recoveryBlocked = (state == DaemonState.RecoveryBlocked),
                    lastRecoverableError = null // TODO: Track recoverable errors
                )
            } ?: DaemonHealthStatus(
                lifecycleState = "Stopped",
                recoveryBlocked = false,
                lastRecoverableError = null
            )
        }

        /** Static holder for the current daemon state (updated by the service). */
        @Volatile
        var currentDaemonState: DaemonState? = null
            internal set

        /** Returns the global legacy save directory path used by existing (legacy) torrents. */
        fun getLegacySaveDirectory(context: Context): File? {
            return context.getExternalFilesDir("downloads") ?: context.filesDir
        }
    }

    /** Non-sensitive daemon health status for WebUI display. */
    @kotlinx.serialization.Serializable
    data class DaemonHealthStatus(
        val lifecycleState: String,
        val recoveryBlocked: Boolean,
        val lastRecoverableError: String?
    )

    private var daemonControl: DaemonControl? = null
    private var queueStore: QueueStore? = null
    private var notificationManager: NotificationManager? = null

    /** Current daemon lifecycle state. */
    private val currentState = AtomicReference(DaemonState.Stopped)

    /** Coroutine scope for background tasks (checkpoint timer, recovery). */
    private val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Job for the checkpoint timer. */
    private var checkpointJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDaemon(userInitiated = false)
            ACTION_USER_START -> startDaemon(userInitiated = true)
            ACTION_STOP -> stopDaemon(intent.getBooleanExtra(EXTRA_FORCE_STOP, false))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startDaemon(userInitiated: Boolean) {
        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!checkNotificationPermission()) {
                android.util.Log.w(TAG, "Notification permission denied; cannot start foreground service")
                // Don't call stopSelf() here — MainActivity should have requested permission first.
                // If we reach here, something went wrong with the permission flow.
                currentState.set(DaemonState.Stopped)
                currentDaemonState = DaemonState.Stopped
                return
            }
        }

        if (userInitiated) {
            if (!RecoverySuppressionStore.clearForceStopped(applicationContext)) {
                android.util.Log.e(TAG, "Unable to clear force-stop intent")
                return
            }
        } else if (shouldSuppressAutomaticRecovery()) {
            android.util.Log.i(TAG, "Automatic daemon recovery suppressed after force stop")
            currentState.set(DaemonState.Stopped)
            currentDaemonState = DaemonState.Stopped
            // This invocation arrived through startForegroundService(), so satisfy Android's
            // foreground-service contract before immediately stopping it.
            startForeground(NOTIFICATION_ID, buildNotification())
            cleanupAndStop()
            return
        }

        currentState.set(DaemonState.Starting)
        currentDaemonState = DaemonState.Starting

        // Initialize the daemon control (currently wraps TorrentSession)
        val control = DaemonControlFactory.create()
        if (!control.init(applicationContext)) {
            android.util.Log.e(TAG, "Failed to initialize daemon control: ${control.lastError}")
            currentState.set(DaemonState.Stopped)
            currentDaemonState = DaemonState.Stopped
            stopSelf()
            return
        }
        this.daemonControl = control

        // Initialize queue store with the global legacy save path
        val legacySavePath = TorrentDaemon.getLegacySaveDirectory(applicationContext)?.absolutePath
        val store = FileQueueStore(applicationContext, globalLegacySavePath = legacySavePath)
        this.queueStore = store

        // Initialize destination catalog.
        val catalog = DestinationCatalog(applicationContext)

        // Expose catalog and queue store to the WebUI server.
        TorrentServer.destinationCatalog = catalog
        TorrentServer.queueStore = store

        // Try to recover queue from previous session (in background)
        daemonScope.launch {
            val recoveryResult = tryRecoverQueue(store, control, resumePausedEntries = userInitiated)
            if (recoveryResult == RecoveryResult.Blocked) {
                currentState.set(DaemonState.RecoveryBlocked)
                currentDaemonState = DaemonState.RecoveryBlocked
                android.util.Log.w(TAG, "Recovery blocked; daemon running with empty queue")
            }
        }

        // Start the Ktor WebUI server
        TorrentServer.start(applicationContext)

        // Start as foreground service with notification (MUST be called within 5 seconds of startForegroundService)
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Start checkpoint timer (30-second interval)
        startCheckpointTimer()

        currentState.set(DaemonState.Running)
        currentDaemonState = DaemonState.Running
        android.util.Log.i(TAG, "Daemon started successfully")
    }

    private fun stopDaemon(forceStop: Boolean) {
        if (currentState.get() == DaemonState.Stopped || currentState.get() == DaemonState.Stopping) {
            return
        }

        currentState.set(DaemonState.Stopping)
        currentDaemonState = DaemonState.Stopping
        android.util.Log.i(TAG, "Stopping daemon (force=$forceStop)")

        // Cancel checkpoint timer
        checkpointJob?.cancel()

        val control = daemonControl ?: run {
            cleanupAndStop()
            return
        }

        // A force stop retains the most recent checkpoint for a later explicit user start.
        daemonScope.launch {
            val saveResult = try {
                if (forceStop) {
                    true
                } else {
                    val queue = loadCurrentQueueFromControl(control)
                    withTimeoutOrNull(SAFE_STOP_DEADLINE_MS) {
                        queueStore?.saveQueueIntent(queue)
                    } != null
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to save queue intent during safe stop: ${e.message}")
                false
            }

            if (!saveResult) {
                // Safe stop failed or timed out — remain running with recoverable error
                android.util.Log.w(TAG, "Safe stop failed; daemon remains running")
                currentState.set(DaemonState.Running)
                currentDaemonState = DaemonState.Running
                return@launch
            }

            // Stop the WebUI server
            TorrentServer.stop()

            // Destroy the native session (saves final resume data)
            try {
                control.destroy()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Error destroying native session during stop: ${e.message}")
            }
            this@TorrentDaemon.daemonControl = null

            cleanupAndStop()
            android.util.Log.i(TAG, "Daemon stopped")
        }
    }

    private fun cleanupAndStop() {
        // Stop foreground service and remove notification
        @Suppress("DEPRECATION")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Attempts to recover queue from previous session.
     * @return RecoveryResult.OK if recovery succeeded, RecoveryResult.Blocked if some entries are blocked.
     */
    private suspend fun tryRecoverQueue(
        store: QueueStore,
        control: DaemonControl,
        resumePausedEntries: Boolean
    ): RecoveryResult {
        val queue = try {
            store.loadQueueIntent()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load queue intent during recovery: ${e.message}")
            return RecoveryResult.Blocked
        }

        if (queue.isEmpty()) {
            android.util.Log.i(TAG, "No queue entries to recover")
            return RecoveryResult.OK
        }

        android.util.Log.i(TAG, "Recovering ${queue.size} queue entries")

        // Migrate legacy entries (null destinationPath) to use the global save path.
        val migrated = store.migrateLegacyEntries()
        if (migrated) {
            android.util.Log.i(TAG, "Migrated legacy queue entries to global save path")
        }

        var hadBlockedEntries = false

        for (entry in queue) {
            try {
                // Validate destination if present.
                val isDestinationAvailable = entry.destinationPath?.let { path ->
                    val validation = DirectoryValidationService.validate(applicationContext, path)
                    validation.isValid
                } ?: true // No destination → use global save path (legacy or default)

                if (!isDestinationAvailable && entry.destinationPath != null) {
                    // Destination unavailable — pause and mark for recovery.
                    android.util.Log.w(TAG, "Destination unavailable for ${entry.magnetUri}: ${entry.destinationPath}")
                    EventBus.post(TorrentEvent.DestinationUnavailable(
                        torrentId = entry.hashCode().toLong(),
                        path = entry.destinationPath!!
                    ))
                }

                // Try to load resume data
                val resumeData = control.loadTorrentResumeData(entry.hashCode().toLong())
                if (resumeData != null) {
                    // Add torrent with resume data (libtorrent will use it for fast resume)
                    val torrentId = control.addMagnet(entry.magnetUri)
                    if (torrentId > 0) {
                        // If destination is unavailable, pause immediately.
                        if (!isDestinationAvailable && entry.destinationPath != null) {
                            control.pauseTorrent(torrentId)
                        } else if (entry.isPaused && !resumePausedEntries) {
                            control.pauseTorrent(torrentId)
                        }
                    }
                } else {
                    // No resume data — add as new torrent (will start downloading)
                    val torrentId = control.addMagnet(entry.magnetUri)
                    if (torrentId > 0) {
                        // If destination is unavailable, pause immediately.
                        if (!isDestinationAvailable && entry.destinationPath != null) {
                            control.pauseTorrent(torrentId)
                        } else if (entry.isPaused && !resumePausedEntries) {
                            control.pauseTorrent(torrentId)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to recover queue entry: ${e.message}")
                hadBlockedEntries = true
            }
        }

        return if (hadBlockedEntries) RecoveryResult.Blocked else RecoveryResult.OK
    }

    private fun shouldSuppressAutomaticRecovery(): Boolean {
        if (RecoverySuppressionStore.isForceStopped(applicationContext)) return true

        val forceStopExitTimestamp = ForceStopDetector.lastUserRequestedExitTimestamp(applicationContext)
            ?: return false
        if (RecoverySuppressionStore.hasHandledForceStopExit(applicationContext, forceStopExitTimestamp)) {
            return false
        }

        if (!RecoverySuppressionStore.markDetectedForceStop(applicationContext, forceStopExitTimestamp)) {
            android.util.Log.e(TAG, "Unable to persist detected force-stop intent")
        }
        return true
    }

    private fun loadCurrentQueueFromControl(control: DaemonControl): List<QueueEntry> {
        // Load queue from daemon control (this is a simplified implementation)
        // In production, you'd track the queue state in memory and sync to store
        return emptyList()
    }

    private fun startCheckpointTimer() {
        checkpointJob?.cancel()
        checkpointJob = daemonScope.launch {
            while (true) {
                delay(CHECKPOINT_INTERVAL_MS)
                checkpointResumeData()
            }
        }
    }

    private suspend fun checkpointResumeData() {
        val control = daemonControl ?: return
        try {
            val ids = withContext(Dispatchers.IO) { control.getAllTorrentIds() }
            for (id in ids) {
                try {
                    control.saveTorrentResumeData(id)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to checkpoint resume data for torrent $id: ${e.message}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to checkpoint resume data: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Torrent Daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows torrent download status"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Create an intent to open the app when notification is tapped
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Create an intent to stop the daemon (from notification action)
        val stopIntent = Intent(this, TorrentDaemon::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Torrent Daemon")
            .setContentText("Downloading...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop downloads",
                stopPendingIntent
            )
            .build()
    }

    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true // Permission not required before Android 13
        }
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources
        checkpointJob?.cancel()
        daemonScope.coroutineContext[Job]?.cancel()
        TorrentServer.stop()
        daemonControl?.destroy()
    }

    /** Result of queue recovery attempt. */
    private enum class RecoveryResult { OK, Blocked }
}
