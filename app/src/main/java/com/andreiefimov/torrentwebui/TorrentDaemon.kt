package com.andreiefimov.torrentwebui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
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

        /** Keeps only the authenticated WebUI alive after runtime storage revocation. */
        const val ACTION_PERMISSION_BLOCKED = "com.andreiefimov.torrentwebui.PERMISSION_BLOCKED"

        /** Applies a device-local WebUI port change without touching the native session. */
        const val ACTION_CONFIGURE_WEB_UI_PORT =
            "com.andreiefimov.torrentwebui.CONFIGURE_WEB_UI_PORT"

        /** Intent extra: whether this is a force stop (vs ordinary stop). */
        const val EXTRA_FORCE_STOP = "force_stop"
        private const val EXTRA_WEB_UI_PORT_INPUT = "web_ui_port_input"

        /** Checkpoint interval for native resume data (30 seconds). */
        const val CHECKPOINT_INTERVAL_MS = 30_000L

        /** Safe-stop deadline (5 seconds). */
        const val SAFE_STOP_DEADLINE_MS = 5_000L

        /** Starts the daemon service once all required Android permissions are granted. */
        fun start(context: Context) {
            if (!hasStartupPermissions(context)) return
            val intent = Intent(context, TorrentDaemon::class.java).apply {
                action = ACTION_START
            }
            launchService(context, intent)
        }

        /** Starts authenticated permission guidance without starting the native session. */
        fun startPermissionBlocked(context: Context) {
            val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!notificationGranted || StoragePermissionChecker.isGranted(context) ||
                !StoragePermissionHistory.hasBeenReady(context)) return
            launchService(context, Intent(context, TorrentDaemon::class.java).apply {
                action = ACTION_PERMISSION_BLOCKED
            })
        }

        /** Stops the daemon service with an explicit user action. */
        fun stop(context: Context) {
            val intent = Intent(context, TorrentDaemon::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** Requests a port change only while an existing daemon/recovery owner is alive. */
        fun configureWebUiPort(context: Context, input: String) {
            val state = currentDaemonState ?: DaemonState.Stopped
            if (state == DaemonState.Stopped || state == DaemonState.Stopping) {
                val unavailable = getWebUiPortStatus(context).copy(
                    operationError = "Start downloads before changing the WebUI port."
                )
                currentWebUiPortStatus.set(unavailable)
                return
            }
            try {
                // The Android fallback screen is foreground and the daemon already owns an active
                // foreground service; do not create a new startForegroundService deadline.
                context.startService(Intent(context, TorrentDaemon::class.java).apply {
                    action = ACTION_CONFIGURE_WEB_UI_PORT
                    putExtra(EXTRA_WEB_UI_PORT_INPUT, input)
                })
            } catch (_: Exception) {
                currentWebUiPortStatus.set(
                    getWebUiPortStatus(context).copy(
                        operationError = "Android could not apply the WebUI port change."
                    )
                )
            }
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
            if (!hasStartupPermissions(context)) return
            val intent = Intent(context, TorrentDaemon::class.java).apply {
                action = ACTION_USER_START
            }
            launchService(context, intent)
        }

        private fun hasStartupPermissions(context: Context): Boolean {
            val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!notificationGranted) {
                android.util.Log.w(TAG, "Daemon start blocked: notification permission is not granted")
                return false
            }
            if (!StoragePermissionChecker.isGranted(context)) {
                android.util.Log.w(TAG, "Daemon start blocked: All Files Access is not granted")
                return false
            }
            return true
        }

        private fun launchService(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Unable to launch daemon service", e)
                currentDaemonState = DaemonState.Stopped
                recordRecoverableError("Android could not launch the download service. Retry Start downloads.")
            }
        }

        /** Checks if the daemon is currently running. */
        fun isRunning(context: Context): Boolean {
            // This is a simplified check; in production, you'd track service state.
            return false
        }

        /** Returns the current daemon health status (non-sensitive). */
        fun getHealthStatus(context: Context): DaemonHealthStatus {
            val state = currentDaemonState ?: DaemonState.Stopped
            return DaemonHealthStatus(
                lifecycleState = state.name,
                recoveryBlocked = state == DaemonState.RecoveryBlocked,
                lastRecoverableError = lastRecoverableError.get()
            )
        }

        /** Returns Android-only configured/effective WebUI port state without LAN discovery. */
        internal fun getWebUiPortStatus(context: Context): WebUiPortStatus {
            val configured = SharedPreferencesWebUiPortStore(context).read()
            return currentWebUiPortStatus.get().copy(configuredPort = configured)
        }

        private val lastRecoverableError = AtomicReference<String?>(null)
        private val currentWebUiPortStatus = AtomicReference(WebUiPortStatus())

        internal var daemonControlFactory: () -> DaemonControl = { DaemonControlFactory.create() }

        internal fun resetDaemonControlFactory() {
            daemonControlFactory = { DaemonControlFactory.create() }
        }

        private fun recordRecoverableError(message: String) {
            lastRecoverableError.set(message)
        }

        /** Static holder for the current daemon state (updated by the service). */
        @Volatile
        var currentDaemonState: DaemonState? = null
            internal set

        /** Returns the global legacy save directory path used by existing (legacy) torrents. */
        fun getLegacySaveDirectory(context: Context): File? {
            return context.getExternalFilesDir("downloads") ?: context.filesDir
        }

        /**
         * Uses the queue intent already persisted when each torrent was added or changed.
         * Safe stop must never reconstruct it from the native session or replace it with an
         * empty placeholder.
         */
        internal suspend fun checkpointQueueIntentForSafeStop(store: QueueStore): List<QueueEntry> =
            store.mutateQueueIntent { current -> current }
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
    private val webUiServerController = WebUiServerController(TorrentServer::createEngine)
    private lateinit var webUiPortCoordinator: WebUiPortCoordinator

    /** Current daemon lifecycle state. */
    private val currentState = AtomicReference(DaemonState.Stopped)
    private val lifecycleLock = Any()
    private val startCoalescer = DaemonStartCoalescer()

    /** Coroutine scope for background tasks (checkpoint timer, recovery). */
    private val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Jobs owned by the service lifecycle. */
    private var checkpointJob: Job? = null
    private var recoveryJob: Job? = null
    private var webUiCleanupJob: Job? = null
    private val moveEventJobs = OwnedJobSlot()
    private var permissionTransitionJob: Job? = null
    private val permissionTransitionGuard = PermissionTransitionGuard()
    @Volatile private var permissionBlockedMode = false
    @Volatile private var permissionTransitionFailed = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        webUiPortCoordinator = WebUiPortCoordinator(
            webUiServerController,
            SharedPreferencesWebUiPortStore(applicationContext)
        )
        currentWebUiPortStatus.set(webUiPortCoordinator.status)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return serviceRestartMode(hasIntent = false)
        when (intent.action) {
            ACTION_START -> startDaemonIfInactive(userInitiated = false)
            ACTION_USER_START -> startDaemonIfInactive(userInitiated = true)
            ACTION_PERMISSION_BLOCKED -> startPermissionBlockedMode()
            ACTION_CONFIGURE_WEB_UI_PORT -> configureWebUiPort(
                intent.getStringExtra(EXTRA_WEB_UI_PORT_INPUT).orEmpty()
            )
            ACTION_STOP -> stopDaemon(intent.getBooleanExtra(EXTRA_FORCE_STOP, false))
        }
        return serviceRestartMode(hasIntent = true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Coalesces repeated starts and serializes restoration with permission-blocked teardown. */
    private fun startDaemonIfInactive(userInitiated: Boolean) {
        if (StoragePermissionChecker.isGranted(applicationContext)) {
            val transition = synchronized(lifecycleLock) { permissionTransitionJob }
            if (transition != null) {
                permissionTransitionGuard.supersede()
                daemonScope.launch {
                    transition.join()
                    synchronized(lifecycleLock) {
                        if (permissionTransitionJob === transition) permissionTransitionJob = null
                    }
                    if (permissionTransitionFailed) {
                        recordRecoverableError("Storage safety transition did not complete; retry after restarting the app.")
                        return@launch
                    }
                    if (!leavePermissionBlockedMode()) return@launch
                    startDaemonIfInactive(userInitiated)
                }
                return
            }
            if (permissionTransitionFailed) {
                recordRecoverableError("Storage safety transition did not complete; retry after restarting the app.")
                return
            }
            if (permissionBlockedMode && !leavePermissionBlockedMode()) return
        }
        val decision = synchronized(lifecycleLock) {
            startCoalescer.onStart(currentState.get(), userInitiated)
        }
        when (decision) {
            StartCommandDecision.StartNow -> startDaemon(userInitiated)
            StartCommandDecision.Queued -> android.util.Log.i(TAG, "Queued daemon start until safe stop completes")
            StartCommandDecision.Ignored -> android.util.Log.i(TAG, "Ignoring duplicate daemon start command")
        }
    }

    private fun startWebUiServer() {
        if (webUiServerController.isRunning) {
            currentWebUiPortStatus.set(webUiPortCoordinator.status)
            return
        }
        TorrentServer.prepare(applicationContext)
        val status = webUiPortCoordinator.startConfigured()
        currentWebUiPortStatus.set(status)
        if (status.effectivePort == null) {
            android.util.Log.e(TAG, status.operationError ?: "WebUI server failed to start")
        }
    }

    private fun configureWebUiPort(input: String) {
        val status = webUiPortCoordinator.apply(input)
        currentWebUiPortStatus.set(status)
        status.operationError?.let { android.util.Log.w(TAG, it) }
        if (status.operationError == WebUiPort.OLD_SERVER_CLEANUP_ERROR &&
            webUiCleanupJob?.isActive != true
        ) {
            webUiCleanupJob = daemonScope.launch {
                while (true) {
                    delay(500)
                    val retried = webUiPortCoordinator.retryRetiredServers()
                    currentWebUiPortStatus.set(retried)
                    if (retried.operationError != WebUiPort.OLD_SERVER_CLEANUP_ERROR) return@launch
                }
            }
        }
    }

    private fun leavePermissionBlockedMode(): Boolean {
        if (!stopWebUiServer()) {
            permissionTransitionFailed = true
            recordRecoverableError("WebUI shutdown did not complete; retry after restarting the app.")
            return false
        }
        val blockedControl = daemonControl
        if (blockedControl === PermissionBlockedDaemonControl) blockedControl.destroy()
        permissionBlockedMode = false
        permissionTransitionFailed = false
        daemonControl = null
        currentState.set(DaemonState.Stopped)
        currentDaemonState = DaemonState.Stopped
        return true
    }

    private fun stopWebUiServer(): Boolean {
        webUiCleanupJob?.cancel()
        webUiCleanupJob = null
        repeat(2) { attempt ->
            if (webUiPortCoordinator.stop() == WebUiServerStopResult.Stopped) {
                currentWebUiPortStatus.set(webUiPortCoordinator.status)
                return true
            }
            android.util.Log.w(TAG, "WebUI server shutdown requires retry ${attempt + 1}")
        }
        currentWebUiPortStatus.set(webUiPortCoordinator.status)
        return false
    }

    private fun startPermissionBlockedMode() {
        if (StoragePermissionChecker.isGranted(applicationContext) ||
            !StoragePermissionHistory.hasBeenReady(applicationContext)) return
        val generation = synchronized(lifecycleLock) {
            if (permissionBlockedMode || permissionTransitionJob != null) return
            permissionBlockedMode = true
            permissionTransitionFailed = false
            permissionTransitionGuard.begin()
        }
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to start permission-blocked WebUI service", e)
            synchronized(lifecycleLock) { permissionBlockedMode = false }
            stopSelf()
            return
        }
        currentState.set(DaemonState.RecoveryBlocked)
        currentDaemonState = DaemonState.RecoveryBlocked
        recordRecoverableError("Storage permission required. Restore All Files Access and explicitly restart downloads.")
        val transition = daemonScope.launch {
            val existingControl = daemonControl
            val store = queueStore ?: FileQueueStore(
                applicationContext,
                globalLegacySavePath = getLegacySaveDirectory(applicationContext)?.absolutePath
            )
            try {
                // Durability is the first side effect: a crash after this point cannot auto-resume.
                store.mutateQueueIntent { queue -> queue.map { it.copy(storagePauseRequired = true) } }
            } catch (_: Exception) {
                permissionTransitionFailed = true
                recordRecoverableError("Unable to persist storage safety pause; restart the app before continuing.")
                return@launch
            }
            existingControl?.getAllTorrentIds()?.forEach(existingControl::pauseTorrent)
            stopMoveEventCollector()
            if (existingControl != null && !existingControl.destroy()) {
                permissionTransitionFailed = true
                recordRecoverableError("Torrent engine could not enter storage-safe mode.")
                return@launch
            }
            daemonControl = null
            AlertDispatcher.stop()
            val mayPublish = synchronized(lifecycleLock) {
                permissionTransitionGuard.isCurrent(generation) &&
                    !StoragePermissionChecker.isGranted(applicationContext)
            }
            if (!mayPublish) return@launch
            daemonControl = PermissionBlockedDaemonControl
            queueStore = store
            TorrentServer.daemonControl = PermissionBlockedDaemonControl
            TorrentServer.queueStore = store
            TorrentServer.destinationCatalog = DestinationCatalog(applicationContext)
            TorrentServer.queueBindings = QueueRuntimeBindings()
            TorrentServer.durableOperations = null
            TorrentServer.moveJournal = MoveJournal(applicationContext)
            TorrentServer.moveService = null
            if (!stopWebUiServer()) {
                permissionTransitionFailed = true
                recordRecoverableError("WebUI storage-safety transition did not complete; restart the app before continuing.")
                return@launch
            }
            startWebUiServer()
            android.util.Log.w(TAG, "Authenticated WebUI running in storage-permission-required mode")
        }
        synchronized(lifecycleLock) { permissionTransitionJob = transition }
        transition.invokeOnCompletion {
            synchronized(lifecycleLock) {
                if (permissionTransitionJob === transition && !StoragePermissionChecker.isGranted(applicationContext)) {
                    permissionTransitionJob = null
                }
            }
        }
    }

    private fun startDaemon(userInitiated: Boolean) {
        if (!hasStartupPermissions(applicationContext)) {
            recordRecoverableError("Required notification or storage permission is unavailable.")
            currentState.set(DaemonState.Stopped)
            currentDaemonState = DaemonState.Stopped
            // A permission revocation can race startForegroundService(). Satisfy Android's
            // five-second service contract, then stop without initializing any daemon work.
            try {
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
                cleanupAndStop()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to clean up permission-blocked foreground service", e)
                stopSelf()
            }
            return
        }

        // Satisfy Android's foreground-service contract immediately.
        // This MUST be called within 5 seconds of startForegroundService().
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to start foreground service", e)
            recordRecoverableError("Android could not start the download foreground service.")
            currentState.set(DaemonState.Stopped)
            currentDaemonState = DaemonState.Stopped
            stopSelf()
            return
        }

        if (userInitiated) {
            if (!RecoverySuppressionStore.clearForceStopped(applicationContext)) {
                android.util.Log.e(TAG, "Unable to clear force-stop intent")
                recordRecoverableError("Unable to save the requested daemon restart state.")
                currentState.set(DaemonState.Stopped)
                currentDaemonState = DaemonState.Stopped
                cleanupAndStop()
                return
            }
        } else if (shouldSuppressAutomaticRecovery()) {
            android.util.Log.i(TAG, "Automatic daemon recovery suppressed after force stop")
            currentState.set(DaemonState.Stopped)
            currentDaemonState = DaemonState.Stopped
            cleanupAndStop()
            return
        }

        currentState.set(DaemonState.Starting)
        currentDaemonState = DaemonState.Starting

        // Durable recovery records must be readable before native ownership is created.
        val legacySavePath = TorrentDaemon.getLegacySaveDirectory(applicationContext)?.absolutePath
        val store = FileQueueStore(applicationContext, globalLegacySavePath = legacySavePath)
        val moveJournal = MoveJournal(applicationContext)
        val recoveryRecordsValid = runBlocking(Dispatchers.IO) {
            validateMoveRecoveryRecords(store, moveJournal)
        }
        if (!recoveryRecordsValid) {
            enterJournalRecoveryBlockedMode(store, moveJournal)
            return
        }

        // Initialize the daemon control (currently wraps TorrentSession)
        val control = daemonControlFactory()
        if (!control.init(applicationContext)) {
            android.util.Log.e(TAG, "Failed to initialize daemon control: ${control.lastError}")
            recordRecoverableError(
                control.lastError?.takeIf { it.isNotBlank() }
                    ?: "Torrent engine failed to initialize. Retry Start downloads."
            )
            currentState.set(DaemonState.Stopped)
            currentDaemonState = DaemonState.Stopped
            cleanupAndStop()
            return
        }
        this.daemonControl = control
        TorrentServer.daemonControl = control

        this.queueStore = store

        // Initialize destination catalog.
        val catalog = DestinationCatalog(applicationContext)

        // Expose shared storage and durable operation seams to the WebUI server.
        val bindings = QueueRuntimeBindings()
        TorrentServer.destinationCatalog = catalog
        TorrentServer.queueStore = store
        TorrentServer.queueBindings = bindings
        TorrentServer.durableOperations = DurableTorrentOperations(control, store, bindings)

        // Initialize move service after the validated journal is bound.
        val debuggable = applicationContext.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val moveGate = if (debuggable) DebugFileMoveExecutionGate() else MoveExecutionGate.None
        val moveService = MoveService(applicationContext, store, moveJournal, control, bindings, moveGate)
        TorrentServer.moveJournal = moveJournal
        TorrentServer.moveService = moveService

        // Try to recover queue from previous session (in background), owned by this service.
        val launchedRecovery = daemonScope.launch {
            val recoveryResult = tryRecoverQueue(store, control)
            if (recoveryResult == RecoveryResult.Blocked && currentState.get() != DaemonState.Stopping) {
                currentState.set(DaemonState.RecoveryBlocked)
                currentDaemonState = DaemonState.RecoveryBlocked
                android.util.Log.w(TAG, "Recovery blocked; daemon running with partial queue")
            }

            // Old move journals lack durable queue identity. Preserve them without guessing.
            recoverInterruptedMoves(control)
        }
        synchronized(lifecycleLock) { recoveryJob = launchedRecovery }

        // Observe move completion/failure events and advance the journal state machine.
        replaceMoveEventCollector(control)

        // Start native alert polling after event observers are ready so asynchronous
        // move completion/failure cannot be consumed without advancing the journal.
        AlertDispatcher.start()

        // Start the Ktor WebUI server without coupling it to native-session ownership.
        startWebUiServer()

        // Start as foreground service with notification (MUST be called within 5 seconds of startForegroundService)
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Start checkpoint timer (30-second interval)
        startCheckpointTimer()

        currentState.set(DaemonState.Running)
        currentDaemonState = DaemonState.Running
        StoragePermissionHistory.markReady(applicationContext)
        lastRecoverableError.set(null)
        android.util.Log.i(TAG, "Daemon started successfully")
    }

    /** Starts only the authenticated recovery surface; no native session/torrent is created. */
    private fun enterJournalRecoveryBlockedMode(store: QueueStore, journal: MoveJournal) {
        val control = RecoveryBlockedDaemonControl
        daemonControl = control
        queueStore = store
        TorrentServer.daemonControl = control
        TorrentServer.queueStore = store
        TorrentServer.queueBindings = QueueRuntimeBindings()
        TorrentServer.destinationCatalog = DestinationCatalog(applicationContext)
        TorrentServer.durableOperations = null
        TorrentServer.moveJournal = journal
        TorrentServer.moveService = null
        currentState.set(DaemonState.RecoveryBlocked)
        currentDaemonState = DaemonState.RecoveryBlocked
        recordRecoverableError("Durable move recovery is blocked. Preserve state and use explicit acceptance teardown or operator recovery.")
        startWebUiServer()
        startForeground(NOTIFICATION_ID, buildNotification())
        android.util.Log.e(TAG, "Move journal validation blocked native recovery")
    }

    private fun stopDaemon(forceStop: Boolean) {
        if (currentState.get() == DaemonState.Stopping) return
        if (currentState.get() == DaemonState.Stopped) {
            // ACTION_STOP may create an otherwise-idle service; do not leave it sticky.
            cleanupAndStop()
            return
        }

        synchronized(lifecycleLock) {
            currentState.set(DaemonState.Stopping)
            currentDaemonState = DaemonState.Stopping
        }
        android.util.Log.i(TAG, "Stopping daemon (force=$forceStop)")

        // Cancel checkpoint timer
        checkpointJob?.cancel()

        val control = daemonControl ?: run {
            if (!stopWebUiServer()) {
                recordRecoverableError("WebUI shutdown did not complete; retry Stop downloads.")
                synchronized(lifecycleLock) {
                    startCoalescer.takePendingAfterStop(stopSucceeded = false)
                    currentState.set(DaemonState.RecoveryBlocked)
                    currentDaemonState = DaemonState.RecoveryBlocked
                }
                return
            }
            val pendingStart = synchronized(lifecycleLock) {
                cleanupAndStop()
                startCoalescer.takePendingAfterStop(stopSucceeded = true)
            }
            pendingStart?.let(::dispatchStartAfterStop)
            return
        }

        // A force stop retains the most recent checkpoint for a later explicit user start.
        daemonScope.launch {
            val saveResult = try {
                if (forceStop) {
                    true
                } else {
                    val store = queueStore ?: throw IllegalStateException("Queue store unavailable")
                    withTimeoutOrNull(SAFE_STOP_DEADLINE_MS) {
                        checkpointQueueIntentForSafeStop(store)
                    } != null
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to save queue intent during safe stop: ${e.message}")
                false
            }

            if (!saveResult) {
                // Safe stop failed or timed out — remain running with recoverable error
                android.util.Log.w(TAG, "Safe stop failed; daemon remains running")
                recordRecoverableError("Unable to checkpoint the download queue; downloads remain running.")
                synchronized(lifecycleLock) {
                    startCoalescer.takePendingAfterStop(stopSucceeded = false)
                    currentState.set(DaemonState.Running)
                    currentDaemonState = DaemonState.Running
                }
                startCheckpointTimer()
                return@launch
            }

            val recovery = synchronized(lifecycleLock) {
                recoveryJob.also { recoveryJob = null }
            }
            recovery?.cancelAndJoin()
            stopMoveEventCollector()

            // Native destruction must succeed before the server or foreground owner is removed.
            if (!control.destroy()) {
                recordRecoverableError(
                    control.lastError?.takeIf { it.isNotBlank() }
                        ?: "Torrent engine could not stop safely; downloads remain running."
                )
                synchronized(lifecycleLock) {
                    startCoalescer.takePendingAfterStop(stopSucceeded = false)
                    currentState.set(DaemonState.Running)
                    currentDaemonState = DaemonState.Running
                }
                // Stop failed, so restore the sole move-event consumer before accepting work.
                replaceMoveEventCollector(control)
                startCheckpointTimer()
                return@launch
            }
            this@TorrentDaemon.daemonControl = null
            if (!stopWebUiServer()) {
                recordRecoverableError("Torrent engine stopped, but WebUI shutdown requires another Stop downloads request.")
                synchronized(lifecycleLock) {
                    startCoalescer.takePendingAfterStop(stopSucceeded = false)
                    currentState.set(DaemonState.RecoveryBlocked)
                    currentDaemonState = DaemonState.RecoveryBlocked
                }
                return@launch
            }

            val pendingStart = synchronized(lifecycleLock) {
                cleanupAndStop()
                startCoalescer.takePendingAfterStop(stopSucceeded = true)
            }
            pendingStart?.let(::dispatchStartAfterStop)
            android.util.Log.i(TAG, "Daemon stopped")
        }
    }

    private fun dispatchStartAfterStop(userInitiated: Boolean) {
        val intent = Intent(applicationContext, TorrentDaemon::class.java).apply {
            action = if (userInitiated) ACTION_USER_START else ACTION_START
        }
        launchService(applicationContext, intent)
    }

    private suspend fun stopMoveEventCollector() {
        moveEventJobs.stop()
    }

    private fun replaceMoveEventCollector(control: DaemonControl) {
        runBlocking {
            moveEventJobs.replace(daemonScope) {
                EventBus.observeTorrentEvents().collect { event ->
                    when (event) {
                        is TorrentEvent.MoveCompleted -> handleMoveCompleted(event, control)
                        is TorrentEvent.MoveFailed -> handleMoveFailed(event, control)
                        is TorrentEvent.VerificationCompleted -> handleVerificationCompleted(event, control)
                        is TorrentEvent.VerificationFailed -> handleVerificationFailed(event, control)
                        else -> {}
                    }
                }
            }
        }
    }

    private fun cleanupAndStop() {
        moveEventJobs.cancel()
        AlertDispatcher.stop()
        currentState.set(DaemonState.Stopped)
        currentDaemonState = DaemonState.Stopped
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
        control: DaemonControl
    ): RecoveryResult {
        val queue = try {
            // Migrate first, then reload so every recovered entry has its durable destination.
            store.migrateLegacyEntries()
            store.loadQueueIntent()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            android.util.Log.e(TAG, "Failed to load queue intent during recovery")
            return RecoveryResult.Blocked
        }

        if (queue.isEmpty()) {
            android.util.Log.i(TAG, "No queue entries to recover")
            return RecoveryResult.OK
        }

        android.util.Log.i(TAG, "Recovering ${queue.size} queue entries")

        var hadBlockedEntries = false

        for (entry in queue) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                val destinationPath = entry.destinationPath ?: store.globalLegacySavePath
                if (destinationPath == null) {
                    hadBlockedEntries = true
                    android.util.Log.w(TAG, "Queue entry has no recoverable destination")
                    continue
                }
                val isDestinationAvailable = DirectoryValidationService
                    .validate(applicationContext, destinationPath)
                    .isValid

                if (!isDestinationAvailable) {
                    // Persist the safety pause before native recovery can touch the path.
                    TorrentServer.durableOperations?.ensureQueueDestinationAvailable(entry.queueId, applicationContext)
                    EventBus.post(TorrentEvent.DestinationUnavailable(
                        torrentId = entry.hashCode().toLong(),
                        path = destinationPath
                    ))
                }

                // Queue intent is the durable source of truth. Safety/user pauses are part of
                // the native add request so an unavailable path is never active even briefly.
                val torrentId = control.addMagnet(
                    entry.magnetUri,
                    recoveryAddRequest(entry, destinationPath, isDestinationAvailable)
                )
                if (torrentId > 0) {
                    TorrentServer.queueBindings?.bind(entry.queueId, torrentId)
                } else {
                    hadBlockedEntries = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                android.util.Log.w(TAG, "Failed to recover queue entry")
                hadBlockedEntries = true
            }
        }

        return if (hadBlockedEntries) RecoveryResult.Blocked else RecoveryResult.OK
    }

    /**
     * Migrates v1 move journal to v2 (QueueId-backed) and recovers interrupted moves.
     */
    private suspend fun recoverInterruptedMoves(control: DaemonControl) {
        val store = queueStore ?: return
        val journal = TorrentServer.moveJournal ?: return
        val bindings = TorrentServer.queueBindings ?: return

        // Migrate v1 journal to v2 (QueueId-backed).
        try {
            val migrated = journal.migrateV1Journal(store)
            if (migrated > 0) {
                android.util.Log.i(TAG, "Migrated $migrated move journal entries to v2")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            android.util.Log.e(TAG, "Failed to migrate move journal")
            return
        }

        // Any non-terminal move journal that survived a daemon/process restart was
        // interrupted by definition. Persist the recovery state before exposing it.
        try {
            journal.getActiveMoves().forEach { move ->
                journal.updatePhase(move.queueId, MovePhase.Interrupted)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to mark active moves interrupted")
            return
        }

        // Recover interrupted moves.
        val interruptedMoves = try {
            journal.getInterruptedMoves()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            android.util.Log.e(TAG, "Failed to load move journal")
            return
        }

        if (interruptedMoves.isEmpty()) {
            android.util.Log.i(TAG, "No interrupted moves to recover")
            return
        }

        android.util.Log.w(TAG, "Found ${interruptedMoves.size} interrupted move(s) — preserving both source and target")

        for (move in interruptedMoves) {
            try {
                restoreSourceBackup(move, bindings.runtimeIdFor(move.queueId), control)
                val runtimeId = bindings.runtimeIdFor(move.queueId)
                if (runtimeId != null) {
                    control.pauseTorrent(runtimeId)
                    android.util.Log.w(TAG, "Paused torrent after interrupted move")
                    EventBus.post(TorrentEvent.MoveInterrupted(
                        torrentId = runtimeId,
                        sourcePath = move.sourcePath,
                        targetPath = move.targetPath
                    ))
                } else {
                    android.util.Log.w(TAG, "Interrupted move has no runtime binding — journal retained")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                android.util.Log.w(TAG, "Failed to recover interrupted move")
            }
        }
    }

    /** Starts normal libtorrent piece verification after native storage movement. */
    private suspend fun handleMoveCompleted(
        event: TorrentEvent.MoveCompleted,
        control: DaemonControl
    ) {
        val journal = TorrentServer.moveJournal ?: return
        val bindings = TorrentServer.queueBindings ?: return
        val queueId = bindings.queueIdFor(event.torrentId) ?: return
        val entry = journal.getMove(queueId) ?: return
        if (entry.phase != MovePhase.Copying) return

        if (!journal.updatePhase(queueId, MovePhase.Verifying) || !control.verifyTorrent(event.torrentId)) {
            journal.updatePhase(queueId, MovePhase.Interrupted)
            control.pauseTorrent(event.torrentId)
            android.util.Log.w(TAG, "Move verification could not start; recovery is required")
        }
    }

    /** Commits the durable destination only after libtorrent piece verification succeeds. */
    private suspend fun handleVerificationCompleted(
        event: TorrentEvent.VerificationCompleted,
        control: DaemonControl
    ) {
        val store = queueStore ?: return
        val journal = TorrentServer.moveJournal ?: return
        val bindings = TorrentServer.queueBindings ?: return
        val queueId = bindings.queueIdFor(event.torrentId) ?: return
        val entry = journal.getMove(queueId) ?: return
        if (entry.phase != MovePhase.Verifying) return

        val torrentName = control.getTorrentStatus(event.torrentId)?.name?.takeIf { it.isNotBlank() }
            ?: run {
                journal.updatePhase(queueId, MovePhase.Interrupted)
                control.pauseTorrent(event.torrentId)
                return
            }
        if (entry.verifyExistingData) {
            val preservedSource = entry.sourceBackupPath?.let {
                resolveMoveOwnedPath(entry.sourcePath, it)
            }
            val verifiedTarget = resolveMoveOwnedChild(entry.targetPath, torrentName)
            val verifiedTargetProgress = withTimeoutOrNull(5_000L) {
                var progress: Float
                do {
                    progress = control.getTorrentStatus(event.torrentId)?.progress ?: 0f
                    if (progress + 0.0005f < entry.sourceVerifiedProgress) delay(100)
                } while (progress + 0.0005f < entry.sourceVerifiedProgress)
                progress
            } ?: -1f
            if (!verifiedTargetRetainsSourceProgress(
                    preservedSource,
                    verifiedTarget,
                    entry.sourceVerifiedProgress,
                    verifiedTargetProgress
                )
            ) {
                handleVerificationFailed(TorrentEvent.VerificationFailed(event.torrentId), control)
                return
            }
        }
        try {
            check(control.pauseTorrent(event.torrentId)) { "Unable to pause after target verification" }
            val updatedQueue = store.mutateQueueIntent { queue ->
                var found = false
                queue.map { qEntry ->
                    if (qEntry.queueId == queueId) {
                        found = true
                        qEntry.copy(destinationPath = entry.targetPath)
                    } else qEntry
                }.also { require(found) { "Move queue entry disappeared" } }
            }
            check(updatedQueue.any { it.queueId == queueId && it.destinationPath == entry.targetPath })
            if (!journal.updatePhase(queueId, MovePhase.QueueUpdated)) {
                // Journal durability is part of the transaction. Restore the durable source
                // destination while the source-side backup is still intact.
                store.mutateQueueIntent { queue ->
                    queue.map { qEntry ->
                        if (qEntry.queueId == queueId) qEntry.copy(destinationPath = entry.sourcePath) else qEntry
                    }
                }
                error("Unable to persist queue-updated move phase")
            }
            if (control is TorrentSession) control.commitTorrentSavePath(event.torrentId, entry.targetPath)

            val sourceOwned = resolveMoveOwnedChild(entry.sourcePath, torrentName)
                ?: error("Unsafe torrent-owned source path")
            check(deleteMoveOwnedPath(entry.sourcePath, sourceOwned.path)) {
                "Unable to remove torrent-owned source after queue update"
            }
            check(entry.sourceBackupPath == null || deleteMoveOwnedPath(entry.sourcePath, entry.sourceBackupPath)) {
                "Unable to remove retained source backup after queue update"
            }
            check(journal.updatePhase(queueId, MovePhase.SourceRemoved))
            check(journal.updatePhase(queueId, MovePhase.Completed))
            // Completed is terminal and intentionally retained until torrent removal/acceptance cleanup.
            android.util.Log.i(TAG, "Move verified and completed for torrent")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            android.util.Log.e(TAG, "Failed to finalize verified move")
            rollbackVerifiedMove(queueId, entry, event.torrentId, control, torrentName)
            control.pauseTorrent(event.torrentId)
        }
    }

    /** Rolls a post-verification failure back to the journal's durable source authority. */
    private suspend fun rollbackVerifiedMove(
        queueId: QueueId,
        entry: PersistedMoveEntry,
        runtimeId: Long,
        control: DaemonControl,
        torrentName: String
    ): Boolean {
        val source = resolveMoveOwnedChild(entry.sourcePath, torrentName) ?: return false
        val backup = entry.sourceBackupPath?.let { resolveMoveOwnedPath(entry.sourcePath, it) }
        val target = resolveMoveOwnedChild(entry.targetPath, torrentName)
        val sourceRestored = when {
            source.exists() -> true
            backup?.exists() == true -> backup.renameTo(source)
            target?.exists() == true -> target.copyRecursively(source, overwrite = false)
            else -> false
        }
        val queueRestored = try {
            val restored = checkNotNull(queueStore).mutateQueueIntent { queue ->
                queue.map { qEntry ->
                    if (qEntry.queueId == queueId) qEntry.copy(destinationPath = entry.sourcePath) else qEntry
                }
            }
            restored.any { it.queueId == queueId && it.destinationPath == entry.sourcePath }
        } catch (_: Exception) {
            false
        }
        val nativeRestored = sourceRestored && queueRestored && control.rollbackStorage(runtimeId, entry.sourcePath)
        if (!nativeRestored) return false
        val journalRestored = TorrentServer.moveJournal?.updatePhase(queueId, MovePhase.Interrupted) == true
        return journalRestored
    }

    /** Keeps both locations recoverable when target piece verification fails. */
    private suspend fun handleVerificationFailed(
        event: TorrentEvent.VerificationFailed,
        control: DaemonControl
    ) {
        val journal = TorrentServer.moveJournal ?: return
        val bindings = TorrentServer.queueBindings ?: return
        val queueId = bindings.queueIdFor(event.torrentId) ?: return
        val entry = journal.getMove(queueId) ?: return
        if (entry.phase != MovePhase.Verifying) return
        control.pauseTorrent(event.torrentId)
        val sourceRestored = restoreSourceBackup(entry, event.torrentId, control)
        val nativeRestored = sourceRestored && control.rollbackStorage(event.torrentId, entry.sourcePath)
        if (nativeRestored) {
            journal.updatePhase(
                queueId,
                if (entry.verifyExistingData) MovePhase.StorageConflict else MovePhase.Interrupted
            )
            android.util.Log.w(TAG, "Move target verification failed; recovery is required")
        } else {
            android.util.Log.e(TAG, "Move target verification failed and native rollback is incomplete")
        }
    }

    /**
     * Handles a native move failure.
     * Marks the journal as interrupted and pauses the torrent; native tracking still holds source.
     */
    private suspend fun handleMoveFailed(
        event: TorrentEvent.MoveFailed,
        control: DaemonControl
    ) {
        val journal = TorrentServer.moveJournal ?: return
        val bindings = TorrentServer.queueBindings ?: return

        val queueId = bindings.queueIdFor(event.torrentId) ?: return
        val entry = journal.getMove(queueId) ?: return
        if (entry.phase != MovePhase.Copying && entry.phase != MovePhase.Verifying) return

        control.pauseTorrent(event.torrentId)
        val sourceRestored = restoreSourceBackup(entry, event.torrentId, control)
        val nativeRestored = sourceRestored && control.rollbackStorage(event.torrentId, entry.sourcePath)
        if (!nativeRestored) {
            android.util.Log.e(TAG, "Move failed and native rollback is incomplete")
            return
        }
        if (!journal.updatePhase(queueId, MovePhase.Interrupted)) return

        android.util.Log.w(TAG, "Move failed for torrent; recovery is required")
        EventBus.post(TorrentEvent.MoveInterrupted(
            torrentId = event.torrentId,
            sourcePath = entry.sourcePath,
            targetPath = entry.targetPath
        ))
    }

    private fun restoreSourceBackup(
        entry: PersistedMoveEntry,
        runtimeId: Long?,
        control: DaemonControl
    ): Boolean {
        val backupPath = entry.sourceBackupPath ?: return true
        val backup = resolveMoveOwnedPath(entry.sourcePath, backupPath) ?: return false
        if (!backup.exists()) return true
        val name = runtimeId?.let(control::getTorrentStatus)?.name?.takeIf { it.isNotBlank() } ?: return false
        val original = resolveMoveOwnedChild(entry.sourcePath, name) ?: return false
        if (original.exists()) return true
        return backup.renameTo(original).also { restored ->
            if (!restored) android.util.Log.w(TAG, "Unable to restore retained move source")
        }
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

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources
        checkpointJob?.cancel()
        recoveryJob?.cancel()
        moveEventJobs.cancel()
        daemonScope.coroutineContext[Job]?.cancel()
        if (!stopWebUiServer()) {
            android.util.Log.e(TAG, "WebUI server shutdown remained incomplete during service destruction")
        }
        daemonControl?.destroy()
        TorrentServer.queueBindings?.clear()
    }

    /** Result of queue recovery attempt. */
    private enum class RecoveryResult { OK, Blocked }
}

internal class OwnedJobSlot {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var active: Job? = null

    suspend fun replace(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job =
        mutex.withLock {
            active?.cancelAndJoin()
            scope.launch(block = block).also { active = it }
        }

    suspend fun stop() {
        mutex.withLock {
            val current = kotlinx.coroutines.currentCoroutineContext()[Job]
            active?.takeIf { it !== current }?.cancelAndJoin()
            active = null
        }
    }

    fun cancel() {
        active?.cancel()
        active = null
    }
}

internal suspend fun validateMoveRecoveryRecords(store: QueueStore, journal: MoveJournal): Boolean =
    try {
        store.migrateLegacyEntries()
        journal.validateForRecovery(store)
        true
    } catch (_: Exception) {
        false
    }

internal fun recoveryAddRequest(
    entry: QueueEntry,
    destinationPath: String,
    isDestinationAvailable: Boolean
): TorrentAddRequest = TorrentAddRequest(
    destination = TorrentDestination(destinationPath),
    startPaused = !isDestinationAvailable || entry.isPaused || entry.storagePauseRequired || entry.addCollisionState != null
)

internal fun serviceRestartMode(hasIntent: Boolean): Int =
    if (hasIntent) Service.START_STICKY else Service.START_NOT_STICKY

internal enum class StartCommandDecision { StartNow, Queued, Ignored }

/** Thread-safe policy for coalescing one start request while a safe stop is in progress. */
internal class PermissionTransitionGuard {
    private var generation = 0L

    @Synchronized
    fun begin(): Long = ++generation

    @Synchronized
    fun supersede(): Long = ++generation

    @Synchronized
    fun isCurrent(candidate: Long): Boolean = candidate == generation
}

internal class DaemonStartCoalescer {
    private var pendingUserInitiated: Boolean? = null

    @Synchronized
    fun onStart(
        state: TorrentDaemon.DaemonState,
        userInitiated: Boolean
    ): StartCommandDecision = when (state) {
        TorrentDaemon.DaemonState.Stopped -> StartCommandDecision.StartNow
        TorrentDaemon.DaemonState.Stopping -> {
            pendingUserInitiated = pendingUserInitiated == true || userInitiated
            StartCommandDecision.Queued
        }
        TorrentDaemon.DaemonState.Starting,
        TorrentDaemon.DaemonState.Running,
        TorrentDaemon.DaemonState.RecoveryBlocked -> StartCommandDecision.Ignored
    }

    @Synchronized
    fun takePendingAfterStop(stopSucceeded: Boolean): Boolean? {
        val pending = pendingUserInitiated
        pendingUserInitiated = null
        return pending?.takeIf { stopSucceeded }
    }
}
