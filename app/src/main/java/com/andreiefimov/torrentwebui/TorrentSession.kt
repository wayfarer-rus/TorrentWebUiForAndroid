package com.andreiefimov.torrentwebui

import android.content.Context
import android.util.Log
import com.andreiefimov.torrentwebui.events.AlertEvent
import com.andreiefimov.torrentwebui.events.EventBus
import com.andreiefimov.torrentwebui.events.SessionEvent
import com.andreiefimov.torrentwebui.events.TorrentEvent

/**
 * Kotlin-facing JNI bridge to libtorrent.
 *
 * The native layer owns all libtorrent objects. Kotlin never directly
 * owns or destroys native handles. This class is the sole JNI entry point.
 */
object TorrentSession {

    private const val TAG = "TorrentSession"
    private var sessionId: Long = 0
    private var _nativeLoaded: Boolean = false
    private var _sessionStarted: Boolean = false
    private var _lastError: String? = null
    private var _version: String = "not loaded"

    val nativeLoaded get() = _nativeLoaded
    val sessionStarted get() = _sessionStarted
    val lastError get() = _lastError
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
            EventBus.post(AlertEvent(type = alertType, message = message))
        }

        override fun onError(torrentId: Long, message: String) {
            EventBus.post(TorrentEvent.Error(torrentId = torrentId, message = message))
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

    fun init(context: Context): Boolean {
        return try {
            System.loadLibrary("torrent-jni")
            _nativeLoaded = true
            _version = nativeVersion()

            val saveDir = context.getExternalFilesDir("downloads")
                ?: context.filesDir
            saveDir.mkdirs()

            sessionId = nativeInit(saveDir.absolutePath)
            if (sessionId > 0) {
                nativeSetSavePath(sessionId, saveDir.absolutePath)
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

    fun destroy() {
        try {
            if (sessionId > 0) {
                nativeDestroy(sessionId)
            }
        } catch (e: Exception) {
            _lastError = e.message
        } finally {
            sessionId = 0
            _sessionStarted = false
            // Notify subscribers that the session is shut down.
            EventBus.post(SessionEvent.Stopped)
        }
    }

    // -------------------------------------------------------------------
    // Torrent operations
    // -------------------------------------------------------------------

    fun addMagnet(magnetUri: String): Long {
        return try {
            if (sessionId <= 0) {
                _lastError = "Session not initialized"
                return -1L
            }
            val torrentId = nativeAddMagnet(sessionId, magnetUri)
            if (torrentId > 0) torrentId else {
                _lastError = "Failed to add magnet"
                -1L
            }
        } catch (e: Exception) {
            _lastError = e.message
            -1L
        }
    }

    fun pauseTorrent(torrentId: Long): Boolean {
        return try {
            if (sessionId <= 0) return false
            nativePauseTorrent(sessionId, torrentId)
        } catch (e: Exception) {
            _lastError = e.message
            false
        }
    }

    fun resumeTorrent(torrentId: Long): Boolean {
        return try {
            if (sessionId <= 0) return false
            nativeResumeTorrent(sessionId, torrentId)
        } catch (e: Exception) {
            _lastError = e.message
            false
        }
    }

    fun removeTorrent(torrentId: Long, deleteFiles: Boolean): Boolean {
        return try {
            if (sessionId <= 0) return false
            nativeRemoveTorrent(sessionId, torrentId, deleteFiles)
        } catch (e: Exception) {
            _lastError = e.message
            false
        }
    }

    // -------------------------------------------------------------------
    // Status queries
    // -------------------------------------------------------------------

    fun getAllTorrentIds(): List<Long> {
        return try {
            if (sessionId <= 0) return emptyList()
            nativeGetAllTorrentIds(sessionId).toList()
        } catch (e: Exception) {
            _lastError = e.message
            emptyList()
        }
    }

    fun getTorrentStatus(torrentId: Long): TorrentStatus? {
        return try {
            if (sessionId <= 0) return null
            val raw = nativeGetTorrentStatus(sessionId, torrentId) ?: return null
            val name = nativeGetTorrentName(sessionId, torrentId)

            TorrentStatus(
                id = raw[0],
                name = name,
                state = stateCodeToString(raw[5].toInt()),
                progress = raw[1].toFloat() / 1000f,
                downloadRate = raw[2],
                uploadRate = raw[3],
                peers = raw[4].toInt(),
                savePath = nativeGetSavePath(sessionId),
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
    fun popAlerts(): String {
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
            Log.w(TAG, "getTorrentIdByHash failed for $hashHex", e)
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


    fun getDiagnostics(): NativeDiagnostics {
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

    private fun stateCodeToString(code: Int): String {
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

    // -------------------------------------------------------------------
    // JNI entry points (extern)
    // -------------------------------------------------------------------

    private external fun nativeInit(savePath: String): Long
    private external fun nativeDestroy(sessionId: Long)
    private external fun nativeVersion(): String
    private external fun nativeAddMagnet(sessionId: Long, magnetUri: String): Long
    private external fun nativePauseTorrent(sessionId: Long, torrentId: Long): Boolean
    private external fun nativeResumeTorrent(sessionId: Long, torrentId: Long): Boolean
    private external fun nativeRemoveTorrent(sessionId: Long, torrentId: Long, deleteFiles: Boolean): Boolean
    private external fun nativeGetTorrentStatus(sessionId: Long, torrentId: Long): LongArray?
    private external fun nativeGetTorrentName(sessionId: Long, torrentId: Long): String
    private external fun nativeGetLastError(sessionId: Long): String?
    private external fun nativeGetAllTorrentIds(sessionId: Long): LongArray
    private external fun nativeSetSavePath(sessionId: Long, path: String)

    private external fun nativeGetSavePath(sessionId: Long): String
}
