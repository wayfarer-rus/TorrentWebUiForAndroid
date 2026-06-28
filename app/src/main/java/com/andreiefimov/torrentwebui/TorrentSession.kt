package com.andreiefimov.torrentwebui

import android.content.Context

/**
 * Kotlin-facing JNI bridge to libtorrent.
 *
 * The native layer owns all libtorrent objects. Kotlin never directly
 * owns or destroys native handles. This class is the sole JNI entry point.
 */
object TorrentSession {

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
                true
            } else {
                _lastError = "Session creation returned id=0"
                false
            }
        } catch (e: Exception) {
            _lastError = e.message ?: e.toString()
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

    fun popAlerts() {
        try {
            if (sessionId > 0) nativePopAlerts(sessionId)
        } catch (e: Exception) {
            _lastError = e.message
        }
    }

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
            0 -> "checking_files"
            1 -> "downloading_metadata"
            2 -> "metadata_received"
            3 -> "downloading"
            4 -> "paused"
            5 -> "seeding"
            6 -> "allocating"
            7 -> "checking_resume_data"
            8 -> "moving_storage"
            9 -> "pause_requested"
            10 -> "queued_for_check"
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
    private external fun nativePopAlerts(sessionId: Long)
    private external fun nativeGetSavePath(sessionId: Long): String
}
