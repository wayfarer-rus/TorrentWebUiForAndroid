package com.andreiefimov.torrentwebui

import android.content.Context
import android.util.Log
import com.andreiefimov.torrentwebui.events.AlertEvent
import com.andreiefimov.torrentwebui.events.EventBus
import com.andreiefimov.torrentwebui.events.SessionEvent
import com.andreiefimov.torrentwebui.events.TorrentEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstraction over torrent session operations used by the Ktor server.
 * Allows the server to be tested without native code.
 */
data class TorrentVerificationResult(val torrentId: Long, val verified: Boolean)

enum class TorrentOwnedDataState { MetadataPending, None, Present }

interface TorrentSessionOps {
    /** Legacy add operation retained for M3 callers; new torrent flows use the typed overload. */
    fun addMagnet(magnetUri: String): Long

    /** Adds a torrent using its explicit canonical destination. */
    fun addMagnet(magnetUri: String, destination: TorrentDestination): Long = addMagnet(magnetUri)

    /** Adds a torrent with an explicit native initial-pause policy. */
    fun addMagnet(magnetUri: String, request: TorrentAddRequest): Long =
        addMagnet(magnetUri, request.destination)

    /**
     * Asynchronously moves torrent data to a new destination.
     * Returns true if the request was accepted by the native engine.
     * Completion is reported via storage_moved_alert (async).
     */
    fun moveStorage(torrentId: Long, targetPath: String): Boolean

    /** Moves storage without replacing a colliding torrent-owned target when reuse is requested. */
    fun moveStorage(torrentId: Long, targetPath: String, reuseExisting: Boolean): Boolean =
        moveStorage(torrentId, targetPath)

    /** Reports whether metadata identifies torrent-owned files already at the save path. */
    fun inspectTorrentOwnedData(torrentId: Long): TorrentOwnedDataState = TorrentOwnedDataState.MetadataPending

    /** Starts normal libtorrent piece verification for the torrent's current storage. */
    fun verifyTorrent(torrentId: Long): Boolean = false

    /** Starts verification and waits for the correlated checked alert. */
    suspend fun verifyTorrentData(torrentId: Long): Boolean = false

    /** Rebinds the live native handle to the durable source and waits for its alert. */
    suspend fun rollbackStorage(torrentId: Long, sourcePath: String): Boolean = false

    fun pauseTorrent(torrentId: Long): Boolean
    fun resumeTorrent(torrentId: Long): Boolean
    fun removeTorrent(torrentId: Long, deleteFiles: Boolean): Boolean
    fun getAllTorrentIds(): List<Long>
    fun getTorrentStatus(torrentId: Long): TorrentStatus?
    fun popAlerts(): String
    val lastError: String?
}

internal fun requireNativeDestroySucceeded(succeeded: Boolean) {
    check(succeeded) { "Native session destruction failed" }
}

/** Runs Kotlin-owned teardown even when native session destruction fails. */
internal fun runNativeDestroyWithCleanup(
    nativeDestroy: () -> Unit,
    cleanup: () -> Unit,
    onFailure: (Exception) -> Unit
): Boolean {
    var succeeded = true
    try {
        nativeDestroy()
    } catch (failure: Exception) {
        succeeded = false
        onFailure(failure)
    } finally {
        cleanup()
    }
    return succeeded
}

/** Correlates one native verification per torrent until its checked alert is drained. */
internal class TorrentVerificationCoordinator {
    private val pending = mutableSetOf<Long>()
    private val waiters = mutableMapOf<Long, CompletableDeferred<Boolean>>()

    @Synchronized
    fun begin(torrentId: Long, waiter: CompletableDeferred<Boolean>? = null): Boolean {
        if (!pending.add(torrentId)) return false
        if (waiter != null) waiters[torrentId] = waiter
        return true
    }

    @Synchronized
    fun failStart(torrentId: Long, waiter: CompletableDeferred<Boolean>? = null) {
        pending.remove(torrentId)
        if (waiter == null || waiters.remove(torrentId, waiter)) waiter?.complete(false)
    }

    /** Detaches a timed-out caller while retaining the pending marker for its stale alert. */
    @Synchronized
    fun detachWaiter(torrentId: Long, waiter: CompletableDeferred<Boolean>) {
        waiters.remove(torrentId, waiter)
    }

    @Synchronized
    fun complete(torrentId: Long): Boolean {
        if (!pending.remove(torrentId)) return false
        waiters.remove(torrentId)?.complete(true)
        return true
    }

    /** Stops a caller waiting on verification, but does not make a stale alert reusable. */
    @Synchronized
    fun abandonWaiter(torrentId: Long) {
        waiters.remove(torrentId)?.complete(false)
    }

    @Synchronized
    fun shutdown() {
        waiters.values.forEach { it.complete(false) }
        waiters.clear()
        pending.clear()
    }
}

/**
 * Kotlin-facing JNI bridge to libtorrent.
 *
 * The native layer owns all libtorrent objects. Kotlin never directly
 * owns or destroys native handles. This class is the sole JNI entry point.
 */
object TorrentSession : DaemonControl {

    private const val TAG = "TorrentSession"
    private var sessionId: Long = 0
    private var _nativeLoaded: Boolean = false
    private var _sessionStarted: Boolean = false
    private var _lastError: String? = null
    private var _version: String = "not loaded"
    private var _storagePermissionState: StoragePermissionState = StoragePermissionState.Ready
    private var legacySavePath: String? = null
    private val pendingStorageRollbacks = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val verificationCoordinator = TorrentVerificationCoordinator()

    val nativeLoaded get() = _nativeLoaded
    val sessionStarted get() = _sessionStarted
    override val lastError get() = _lastError
    override val storagePermissionState: StoragePermissionState get() = _storagePermissionState
    override val isStorageReady: Boolean get() = _storagePermissionState == StoragePermissionState.Ready
    val version get() = _version

    // -------------------------------------------------------------------
    // Alerts — callback interface implementation (for future JNI registration)
    // -------------------------------------------------------------------

    /**
     * Default [AlertReceiver] implementation that posts every alert to [EventBus].
     *
     * Currently alerts are polled via [popAlerts] rather than pushed through JNI callbacks,
     * but this implementation is available for when the native layer adds callback registration.
     */
    class AlertReceiverImpl : com.andreiefimov.torrentwebui.events.AlertReceiver {
        override fun onAlert(alertType: String, message: String) {
            EventBus.post(AlertEvent(type = alertType, message = "Torrent state updated"))
        }

        override fun onError(torrentId: Long, message: String) {
            EventBus.post(TorrentEvent.Error(torrentId = torrentId, message = "Torrent operation failed"))
        }

        override fun onTorrentAdded(torrentId: Long) {
            EventBus.post(TorrentEvent.Added(torrentId = torrentId))
        }

        override fun onTorrentRemoved(torrentId: Long) {
            EventBus.post(TorrentEvent.Removed(torrentId = torrentId))
        }

        override fun onStateChanged(torrentId: Long, newState: String) {
            EventBus.post(TorrentEvent.StateChanged(torrentId = torrentId, newState = newState))
        }
    }

    // -------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------

    override fun init(context: Context): Boolean {
        return try {
            // Check storage permission before starting the native session.
            _storagePermissionState = StoragePermissionChecker.getCurrentState(context)
            if (_storagePermissionState != StoragePermissionState.Ready) {
                _lastError = "Storage permission not granted (state: ${_storagePermissionState.name})"
                EventBus.post(SessionEvent.Error(_lastError ?: ""))
                return false
            }

            System.loadLibrary("torrent-jni")
            _nativeLoaded = true
            _version = nativeVersion()

            val saveDir = context.getExternalFilesDir("downloads")
                ?: context.filesDir
            saveDir.mkdirs()
            legacySavePath = saveDir.canonicalPath

            sessionId = nativeInit(legacySavePath!!)
            if (sessionId > 0) {
                _sessionStarted = true

                // Notify subscribers that the session is ready.
                EventBus.post(SessionEvent.Started)
                true
            } else {
                _lastError = "Session creation returned id=0"
                EventBus.post(SessionEvent.Error(_lastError ?: ""))
                false
            }
        } catch (e: Exception) {
            _lastError = e.message ?: e.toString()
            EventBus.post(SessionEvent.Error(_lastError ?: ""))
            false
        }
    }

    override fun destroy(): Boolean = runNativeDestroyWithCleanup(
        nativeDestroy = {
            if (sessionId > 0) requireNativeDestroySucceeded(nativeDestroy(sessionId))
        },
        onFailure = { failure ->
            _lastError = failure.message ?: "Native session destruction failed"
        },
        cleanup = {
            sessionId = 0
            legacySavePath = null
            pendingStorageRollbacks.values.forEach { it.complete(false) }
            pendingStorageRollbacks.clear()
            verificationCoordinator.shutdown()
            _sessionStarted = false
            // Preserve storage permission state across daemon restarts.
            EventBus.post(SessionEvent.Stopped)
        }
    )

    /**
     * Re-checks the current storage permission state and updates internal state.
     *
     * Call this before storage operations (add/move) to detect runtime permission revocation.
     * If the state transitions from Ready to a denied state, callers should reject the operation
     * and report [StoragePermissionState.RevokedRuntime] in the WebUI.
     */
    override fun refreshStoragePermissionState(context: Context) {
        val previousState = _storagePermissionState
        val granted = StoragePermissionChecker.isGranted(context)
        _storagePermissionState = when {
            granted -> StoragePermissionState.Ready
            previousState == StoragePermissionState.Ready && _sessionStarted -> StoragePermissionState.RevokedRuntime
            previousState == StoragePermissionState.RevokedRuntime ||
                StoragePermissionHistory.hasBeenReady(context) -> StoragePermissionState.RevokedRuntime
            else -> StoragePermissionState.DeniedAtStartup
        }

        if (previousState == StoragePermissionState.Ready && _storagePermissionState == StoragePermissionState.RevokedRuntime) {
            // The daemon transition persists storagePauseRequired before touching native state.
            TorrentDaemon.startPermissionBlocked(context.applicationContext)
        }
    }

    // -------------------------------------------------------------------
    // Torrent operations
    // -------------------------------------------------------------------

    /** Legacy add path for retained M3 callers. New product flows must pass a destination. */
    override fun addMagnet(magnetUri: String): Long {
        val destination = legacySavePath ?: run {
            _lastError = "Session not initialized"
            return -1L
        }
        return addMagnet(magnetUri, TorrentDestination(destination))
    }

    override fun addMagnet(magnetUri: String, destination: TorrentDestination): Long =
        addMagnet(magnetUri, TorrentAddRequest(destination))

    override fun addMagnet(magnetUri: String, request: TorrentAddRequest): Long {
        return try {
            if (sessionId <= 0) {
                _lastError = "Session not initialized"
                return -1L
            }
            if (request.destination.path.isBlank()) {
                _lastError = "Destination path is required"
                return -1L
            }
            val torrentId = nativeAddMagnet(
                sessionId,
                magnetUri,
                request.destination.path,
                request.startPaused,
                request.metadataOnlyUntilVerified
            )
            if (torrentId > 0) torrentId else {
                _lastError = "Failed to add magnet"
                -1L
            }
        } catch (_: Exception) {
            _lastError = "Failed to add torrent"
            -1L
        }
    }

    override fun pauseTorrent(torrentId: Long): Boolean {
        return try {
            if (sessionId <= 0) return false
            nativePauseTorrent(sessionId, torrentId)
        } catch (e: Exception) {
            _lastError = e.message
            false
        }
    }

    override fun resumeTorrent(torrentId: Long): Boolean {
        return try {
            if (sessionId <= 0) return false
            nativeResumeTorrent(sessionId, torrentId)
        } catch (e: Exception) {
            _lastError = e.message
            false
        }
    }

    override fun removeTorrent(torrentId: Long, deleteFiles: Boolean): Boolean {
        return try {
            if (sessionId <= 0) return false
            nativeRemoveTorrent(sessionId, torrentId, deleteFiles)
        } catch (e: Exception) {
            _lastError = e.message
            false
        }
    }

    override fun moveStorage(torrentId: Long, targetPath: String): Boolean =
        moveStorage(torrentId, targetPath, reuseExisting = false)

    override fun moveStorage(torrentId: Long, targetPath: String, reuseExisting: Boolean): Boolean {
        return try {
            if (sessionId <= 0) return false
            nativeMoveStorage(sessionId, torrentId, targetPath, reuseExisting)
        } catch (_: Exception) {
            _lastError = "Torrent storage move failed"
            false
        }
    }

    override fun inspectTorrentOwnedData(torrentId: Long): TorrentOwnedDataState = try {
        TorrentOwnedDataState.entries.getOrElse(nativeInspectTorrentOwnedData(sessionId, torrentId)) {
            TorrentOwnedDataState.MetadataPending
        }
    } catch (_: Exception) {
        TorrentOwnedDataState.MetadataPending
    }

    override fun verifyTorrent(torrentId: Long): Boolean = startTorrentVerification(torrentId)

    override suspend fun verifyTorrentData(torrentId: Long): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        if (!startTorrentVerification(torrentId, waiter)) return false
        return try {
            withTimeoutOrNull(30_000L) { waiter.await() } == true
        } finally {
            verificationCoordinator.detachWaiter(torrentId, waiter)
        }
    }

    private fun startTorrentVerification(
        torrentId: Long,
        waiter: CompletableDeferred<Boolean>? = null
    ): Boolean {
        if (sessionId <= 0 || !verificationCoordinator.begin(torrentId, waiter)) return false
        return try {
            if (nativeVerifyTorrent(sessionId, torrentId)) {
                true
            } else {
                verificationCoordinator.failStart(torrentId, waiter)
                false
            }
        } catch (_: Exception) {
            verificationCoordinator.failStart(torrentId, waiter)
            _lastError = "Torrent verification failed"
            false
        }
    }

    /** Completes a correlated recheck without requiring the torrent itself to be complete. */
    internal fun completeTorrentVerification(torrentId: Long): TorrentVerificationResult? {
        if (!verificationCoordinator.complete(torrentId)) return null
        return TorrentVerificationResult(torrentId = torrentId, verified = true)
    }

    internal fun abandonPendingMoveVerification(torrentId: Long) {
        verificationCoordinator.abandonWaiter(torrentId)
    }

    override suspend fun rollbackStorage(torrentId: Long, sourcePath: String): Boolean {
        abandonPendingMoveVerification(torrentId)
        if (sessionId <= 0) return false
        val completion = CompletableDeferred<Boolean>()
        if (pendingStorageRollbacks.putIfAbsent(torrentId, completion) != null) return false
        return try {
            if (!nativeRollbackStorage(sessionId, torrentId, sourcePath)) return false
            val completed = withTimeoutOrNull(30_000L) { completion.await() } == true
            if (completed) nativeCommitTorrentSavePath(sessionId, torrentId, sourcePath)
            completed
        } catch (_: Exception) {
            _lastError = "Torrent storage rollback failed"
            false
        } finally {
            pendingStorageRollbacks.remove(torrentId, completion)
        }
    }

    internal fun completeStorageRollback(torrentId: Long, succeeded: Boolean): Boolean {
        val pending = pendingStorageRollbacks[torrentId] ?: return false
        pending.complete(succeeded)
        return true
    }

    // -------------------------------------------------------------------
    // Status queries
    // -------------------------------------------------------------------

    override fun getAllTorrentIds(): List<Long> {
        return try {
            if (sessionId <= 0) return emptyList()
            nativeGetAllTorrentIds(sessionId).toList()
        } catch (e: Exception) {
            _lastError = e.message
            emptyList()
        }
    }

    override fun getTorrentStatus(torrentId: Long): TorrentStatus? {
        return try {
            if (sessionId <= 0) return null
            val raw = nativeGetTorrentStatus(sessionId, torrentId) ?: return null
            val name = nativeGetTorrentName(sessionId, torrentId)

            TorrentStatus(
                id = raw[0],
                name = name,
                state = mapState(raw[5].toInt(), raw[6] == 1L),
                progress = raw[1].toFloat() / 1000f,
                downloadRate = raw[2],
                uploadRate = raw[3],
                peers = raw[4].toInt(),
                savePath = nativeGetTorrentSavePath(sessionId, torrentId),
                error = null
            )
        } catch (e: Exception) {
            _lastError = e.message
            null
        }
    }

    fun refreshLastError(): String? {
        return try {
            if (sessionId > 0) nativeGetLastError(sessionId) else null
        } catch (e: Exception) {
            e.message
        }
    }

    /**
     * Pops all pending alerts from the native session and returns them as a JSON array string.
     * Each alert is an object with: type, message, category, info_hash (hex).
     * Returns "[]" if no alerts or on error.
     */
    override fun popAlerts(): String {
        return try {
            if (sessionId <= 0) return "[]"
            val json = nativeGetAllAlerts(sessionId) ?: "[]"
            if (json.isBlank()) "[]" else json
        } catch (e: Exception) {
            Log.w(TAG, "popAlerts failed", e)
            "[]"
        }
    }

    /** Returns a map of torrent ID → info_hash (hex) for all active torrents. */
    fun getAllTorrentHashes(): Map<Long, String> {
        return try {
            if (sessionId <= 0) return emptyMap()
            val hashes = nativeGetAllTorrentHashes(sessionId) ?: return emptyMap()
            val ids = getAllTorrentIds()
            ids.zip(hashes.toList()).filter { it.second.isNotEmpty() }
                .associate { it.first to it.second }
        } catch (e: Exception) {
            Log.w(TAG, "getAllTorrentHashes failed", e)
            emptyMap()
        }
    }

    /** Looks up a torrent ID by its info_hash (hex-encoded). Returns -1 if not found. */
    fun getTorrentIdByHash(hashHex: String): Long {
        return try {
            if (sessionId <= 0) return -1L
            nativeGetTorrentIdByHash(sessionId, hashHex)
        } catch (e: Exception) {
            Log.w(TAG, "getTorrentIdByHash failed", e)
            -1L
        }
    }

    // -------------------------------------------------------------------
    // Alerts (JNI) — returns JSON string of alerts
    // -------------------------------------------------------------------

    private external fun nativeGetAllAlerts(sessionId: Long): String?
    private external fun nativeGetAllTorrentHashes(sessionId: Long): Array<String>?
    private external fun nativeGetTorrentIdByHash(sessionId: Long, hashHex: String): Long

    // -------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------


    override fun getDiagnostics(): NativeDiagnostics {
        return NativeDiagnostics(
            abi = android.os.Build.SUPPORTED_ABIS.getOrNull(0) ?: "unknown",
            libtorrentVersion = _version,
            nativeLoaded = _nativeLoaded,
            sessionStarted = _sessionStarted,
            lastError = _lastError
        )
    }

    // -------------------------------------------------------------------
    // State mapping
    // -------------------------------------------------------------------

    private fun mapState(code: Int, paused: Boolean): String {
        // If the torrent is explicitly paused (pause flag set), report it as paused
        // regardless of current state code. This handles the case where pause() is called
        // during metadata download (state=2) but libtorrent keeps reporting "downloading_metadata".
        if (paused && code != 10) return "paused"

        return when (code) {
            0 -> "queued_for_checking"
            1 -> "checking_files"
            2 -> "downloading_metadata"
            3 -> "downloading"
            4 -> "finished"
            5 -> "seeding"
            6 -> "allocating"
            7 -> "checking_resume_data"
            8 -> "move_storage"
            9 -> "pause_requested"
            10 -> "paused"
            else -> "unknown($code)"
        }
    }

    /** @deprecated Use [mapState] instead. Kept for backward compatibility with any callers that
     *  still reference the old name. */
    @Suppress("unused")
    private fun stateCodeToString(code: Int): String = mapState(code, paused = false)

    // -------------------------------------------------------------------
    // Resume data checkpointing (for M3 persistence)
    // -------------------------------------------------------------------

    override fun saveTorrentResumeData(torrentId: Long): Boolean {
        return try {
            if (sessionId <= 0) return false
            nativeSaveTorrentResumeData(sessionId, torrentId)
        } catch (e: Exception) {
            _lastError = e.message
            false
        }
    }

    override fun loadTorrentResumeData(torrentId: Long): ByteArray? {
        return try {
            if (sessionId <= 0) return null
            nativeLoadTorrentResumeData(sessionId, torrentId)
        } catch (e: Exception) {
            _lastError = e.message
            null
        }
    }

    override fun removeTorrentResumeData(torrentId: Long) {
        try {
            if (sessionId > 0) nativeRemoveTorrentResumeData(sessionId, torrentId)
        } catch (e: Exception) {
            _lastError = e.message
        }
    }

    // -------------------------------------------------------------------
    // JNI entry points (extern)
    // -------------------------------------------------------------------

    private external fun nativeInit(legacySavePath: String): Long
    private external fun nativeDestroy(sessionId: Long): Boolean
    private external fun nativeVersion(): String
    private external fun nativeAddMagnet(
        sessionId: Long,
        magnetUri: String,
        destinationPath: String,
        startPaused: Boolean,
        metadataOnlyUntilVerified: Boolean
    ): Long
    private external fun nativeInspectTorrentOwnedData(sessionId: Long, torrentId: Long): Int
    private external fun nativePauseTorrent(sessionId: Long, torrentId: Long): Boolean
    private external fun nativeResumeTorrent(sessionId: Long, torrentId: Long): Boolean
    private external fun nativeRemoveTorrent(sessionId: Long, torrentId: Long, deleteFiles: Boolean): Boolean
    private external fun nativeGetTorrentStatus(sessionId: Long, torrentId: Long): LongArray?
    private external fun nativeGetTorrentName(sessionId: Long, torrentId: Long): String
    private external fun nativeGetLastError(sessionId: Long): String?
    private external fun nativeGetAllTorrentIds(sessionId: Long): LongArray
    private external fun nativeGetTorrentSavePath(sessionId: Long, torrentId: Long): String
    private external fun nativeSaveTorrentResumeData(sessionId: Long, torrentId: Long): Boolean
    private external fun nativeLoadTorrentResumeData(sessionId: Long, torrentId: Long): ByteArray?
    private external fun nativeRemoveTorrentResumeData(sessionId: Long, torrentId: Long)
    private external fun nativeMoveStorage(
        sessionId: Long,
        torrentId: Long,
        targetPath: String,
        reuseExisting: Boolean
    ): Boolean
    private external fun nativeVerifyTorrent(sessionId: Long, torrentId: Long): Boolean
    private external fun nativeRollbackStorage(sessionId: Long, torrentId: Long, sourcePath: String): Boolean

    /** Commits native status tracking only after the durable queue accepted a completed move. */
    internal fun commitTorrentSavePath(runtimeId: Long, targetPath: String) {
        if (sessionId > 0) nativeCommitTorrentSavePath(sessionId, runtimeId, targetPath)
    }

    private external fun nativeCommitTorrentSavePath(sessionId: Long, torrentId: Long, targetPath: String)
}
