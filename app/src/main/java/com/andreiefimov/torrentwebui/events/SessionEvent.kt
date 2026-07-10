package com.andreiefimov.torrentwebui.events

/**
 * Session-level events dispatched via [EventBus].
 *
 * These are not tied to any specific torrent — they describe the lifecycle
 * and health of the entire libtorrent session.
 */
sealed class SessionEvent : AppEvent {

    /** The native session was successfully initialized and is ready. */
    object Started : SessionEvent()

    /** The native session was shut down (after [TorrentSession.destroy]). */
    object Stopped : SessionEvent()

    /** A fatal or non-recoverable session error occurred. */
    data class Error(val message: String) : SessionEvent()

    /** Session warnings (non-fatal, informational). */
    data class Warning(val message: String) : SessionEvent()
}
