package com.andreiefimov.torrentwebui.events

/**
 * Typed event for a single libtorrent alert forwarded through [EventBus].
 *
 * @property type Alert category/type string (e.g. "torrent_completed", "file_completed").
 * @property message Human-readable alert description.
 */
data class AlertEvent(
    val type: String,
    val message: String,
) : AppEvent

/**
 * Callback interface for the native layer to push alerts into Kotlin.
 *
 * The JNI bridge registers an [AlertReceiverImpl] that implements this interface.
 * When the native alert queue has new entries, it calls back here so Kotlin can
 * forward them to [EventBus] subscribers.
 */
interface AlertReceiver {
    fun onAlert(alertType: String, message: String)
    fun onError(torrentId: Long, message: String)
    fun onTorrentAdded(torrentId: Long)
    fun onTorrentRemoved(torrentId: Long)
    fun onStateChanged(torrentId: Long, newState: String)
}
