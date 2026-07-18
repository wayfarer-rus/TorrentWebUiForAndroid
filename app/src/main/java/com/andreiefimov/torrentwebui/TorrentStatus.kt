package com.andreiefimov.torrentwebui

/**
 * DTO returned from the native libtorrent layer via JNI.
 * Kotlin never owns native objects; this is a pure data carrier.
 */
data class TorrentStatus(
    val id: Long,
    val name: String,
    val state: String,
    val progress: Float,        // 0.0f .. 1.0f
    val downloadRate: Long,     // bytes/sec
    val uploadRate: Long,       // bytes/sec
    val peers: Int,
    val savePath: String,
    val error: String? = null,
    /**
     * Per-torrent destination status. Null means no specific status (normal operation).
     * Set to "destination_unavailable" when the torrent's stored destination path is not
     * accessible (USB disconnect, path deleted, permission revoked).
     */
    val destinationStatus: String? = null,
    /**
     * Move status. Null means no move in progress.
     * Set to "move_interrupted" when a data move was interrupted (crash, USB loss, etc.)
     * and requires explicit user action to retry or cancel.
     */
    val moveStatus: String? = null
)

/**
 * Diagnostics data from the native layer.
 */
data class NativeDiagnostics(
    val abi: String,
    val libtorrentVersion: String,
    val nativeLoaded: Boolean,
    val sessionStarted: Boolean,
    val lastError: String?
)
