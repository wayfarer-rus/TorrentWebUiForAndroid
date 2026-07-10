package com.andreiefimov.torrentwebui.events

/**
 * Typed events from the native torrent engine, dispatched via [EventBus].
 *
 * Each variant carries only the data relevant to that event type, so UI consumers
 * can pattern-match without inspecting unrelated fields.
 */
sealed class TorrentEvent : AppEvent {

    /** A torrent's state changed (downloading → completed, paused, etc.). */
    data class StateChanged(
        val torrentId: Long,
        val newState: String,
    ) : TorrentEvent()

    /** A new torrent was added to the session. */
    data class Added(
        val torrentId: Long,
    ) : TorrentEvent()

    /** A torrent was removed from the session. */
    data class Removed(
        val torrentId: Long,
    ) : TorrentEvent()

    /** An error was associated with a specific torrent (e.g. storage error). */
    data class Error(
        val torrentId: Long,
        val message: String,
    ) : TorrentEvent()

    companion object {
        /** Human-readable name for a torrent state code. */
        fun stateLabel(state: String): String = when (state) {
            "downloading" -> "Downloading"
            "seeding" -> "Seeding"
            "pausedDL" -> "Paused (download)"
            "pausedUP" -> "Paused (upload)"
            "checkingDL" -> "Checking (download)"
            "checkingUP" -> "Checking (upload)"
            "completed" -> "Completed"
            "queuedDL" -> "Queued (download)"
            "queuedUP" -> "Queued (upload)"
            "allocating" -> "Allocating"
            "forcedDL" -> "Forced download"
            "forcedUP" -> "Forced upload"
            "metadata" -> "Downloading metadata"
            "missingFiles" -> "Missing files"
            "error" -> "Error"
            else -> state
        }
    }
}
