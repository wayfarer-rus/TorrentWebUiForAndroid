package com.andreiefimov.torrentwebui

import android.content.Context

/**
 * Unified control seam for the torrent daemon.
 *
 * Combines session operations ([TorrentSessionOps]) with lifecycle management (init/destroy)
 * into a single interface. This is the seam that Android UI and WebUI both talk through,
 * so the native session lifecycle can move behind a foreground service without changing callers.
 *
 * Future milestones swap in different implementations (e.g., a real [android.app.Service]-backed
 * daemon) without touching UI or WebUI code — they just provide a different [DaemonControl].
 *
 * Currently, the production implementation is backed by [TorrentSession] (the JNI bridge).
 */
/** A canonical filesystem path selected for one torrent operation. */
@JvmInline
value class TorrentDestination(val path: String)

interface DaemonControl : TorrentSessionOps {

    /**
     * Returns the current storage-permission state.
     *
     * When not [StoragePermissionState.Ready], storage-changing operations (add with destination,
     * move) are rejected and the WebUI reports the blocked state.
     */
    val storagePermissionState: StoragePermissionState
        get() = StoragePermissionState.Ready // default for implementations that don't override

    /** Returns true if storage operations are currently enabled (permission is ready). */
    val isStorageReady: Boolean
        get() = storagePermissionState == StoragePermissionState.Ready

    /**
     * Re-checks storage permission state from the OS. Call before storage operations
     * to detect runtime revocation.
     */
    fun refreshStoragePermissionState(context: Context) {}

    /**
     * Initialize the native torrent session. Called once before any operations.
     * @return true if initialization succeeded, false otherwise (check [lastError] for details).
     */
    fun init(context: Context): Boolean

    /**
     * Destroy the native torrent session and release resources. Called on shutdown.
     * @return true only when native ownership was released.
     */
    fun destroy(): Boolean

    /**
     * Returns diagnostics data from the native layer (ABI, version, load status, etc.).
     */
    fun getDiagnostics(): NativeDiagnostics

    /**
     * Saves native resume data for a specific torrent. Called during checkpointing.
     * @return true if save was successful, false otherwise.
     */
    fun saveTorrentResumeData(torrentId: Long): Boolean

    /**
     * Loads native resume data for a specific torrent. Called during recovery.
     * @return the resume data, or null if none exists.
     */
    fun loadTorrentResumeData(torrentId: Long): ByteArray?

    /**
     * Removes native resume data for a specific torrent. Called when removing a torrent.
     */
    fun removeTorrentResumeData(torrentId: Long)
}

/**
 * Factory for creating the production [DaemonControl] implementation.
 *
 * Currently returns the singleton [TorrentSession] (JNI-backed). Future milestones will swap
 * in a foreground-service-backed implementation without changing this call site.
 */
object DaemonControlFactory {

    /** Creates the default production [DaemonControl]. */
    fun create(): DaemonControl = TorrentSession

    /**
     * Creates a test [DaemonControl] backed by the given [TorrentSessionOps].
     *
     * The test variant defaults to storage-ready; tests can override via the [storagePermissionState]
     * parameter.
     */
    fun createForTest(sessionOps: TorrentSessionOps, storagePermissionState: StoragePermissionState = StoragePermissionState.Ready): DaemonControl = object : DaemonControl {
        override fun init(context: Context): Boolean = true
        override fun destroy(): Boolean = true
        override fun getDiagnostics(): NativeDiagnostics = NativeDiagnostics(
            abi = "test",
            libtorrentVersion = "test",
            nativeLoaded = true,
            sessionStarted = true,
            lastError = null
        )
        override val storagePermissionState: StoragePermissionState get() = storagePermissionState
        override fun refreshStoragePermissionState(context: Context) {}
        // Delegate all ops to the provided sessionOps.
        override fun addMagnet(magnetUri: String): Long = sessionOps.addMagnet(magnetUri)
        override fun addMagnet(magnetUri: String, destination: TorrentDestination): Long =
            sessionOps.addMagnet(magnetUri, destination)
        override fun pauseTorrent(torrentId: Long): Boolean = sessionOps.pauseTorrent(torrentId)
        override fun resumeTorrent(torrentId: Long): Boolean = sessionOps.resumeTorrent(torrentId)
        override fun removeTorrent(torrentId: Long, deleteFiles: Boolean): Boolean =
            sessionOps.removeTorrent(torrentId, deleteFiles)
        override fun moveStorage(torrentId: Long, targetPath: String): Boolean =
            sessionOps.moveStorage(torrentId, targetPath)
        override fun getAllTorrentIds(): List<Long> = sessionOps.getAllTorrentIds()
        override fun getTorrentStatus(torrentId: Long): TorrentStatus? = sessionOps.getTorrentStatus(torrentId)
        override fun popAlerts(): String = sessionOps.popAlerts()
        override val lastError: String? get() = sessionOps.lastError
        // Resume data methods (test variant always returns null/false)
        override fun saveTorrentResumeData(torrentId: Long): Boolean = false
        override fun loadTorrentResumeData(torrentId: Long): ByteArray? = null
        override fun removeTorrentResumeData(torrentId: Long) {}
    }
}
