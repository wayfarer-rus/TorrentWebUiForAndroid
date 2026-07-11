package com.andreiefimov.torrentwebui.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter

/**
 * Lightweight publish/subscribe event bus for decoupling the native engine from UI.
 *
 * Events are typed via sealed hierarchies ([TorrentEvent], [SessionEvent]) plus the
 * standalone [AlertEvent] so consumers can pattern-match on exactly the events they care about.
 *
 * Thread-safety: [post] and its typed variants are safe to call from any thread
 * (libtorrent alert callbacks, IO dispatcher coroutines, etc.). Subscribers receive
 * events on the context they observe with.
 */
object EventBus {

    private val _flow = MutableSharedFlow<AppEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Observe all events. Call [kotlinx.coroutines.flow.Flow.collect] in a coroutine. */
    fun observe(): Flow<AppEvent> = _flow.asSharedFlow()

    /** Post an event to all current and future subscribers. */
    fun post(event: AppEvent) {
        _flow.tryEmit(event)
    }

    // ------------------------------------------------------------------
    // AlertEvent helpers
    // ------------------------------------------------------------------

    /** Observe only [AlertEvent]s. */
    fun observeAlerts(): Flow<AlertEvent> = _flow.mapNotNull { it as? AlertEvent }

    /** Post an [AlertEvent] to all subscribers. */
    fun postAlert(event: AlertEvent) {
        _flow.tryEmit(event)
    }

    // ------------------------------------------------------------------
    // TorrentEvent helpers
    // ------------------------------------------------------------------

    /** Observe only [TorrentEvent]s. */
    fun observeTorrentEvents(): Flow<TorrentEvent> = _flow.mapNotNull { it as? TorrentEvent }

    /** Post a [TorrentEvent] to all subscribers. */
    fun postTorrentEvent(event: TorrentEvent) {
        _flow.tryEmit(event)
    }

    // ------------------------------------------------------------------
    // SessionEvent helpers
    // ------------------------------------------------------------------

    /** Observe only [SessionEvent]s. */
    fun observeSessionEvents(): Flow<SessionEvent> = _flow.mapNotNull { it as? SessionEvent }

    /** Post a [SessionEvent] to all subscribers. */
    fun postSessionEvent(event: SessionEvent) {
        _flow.tryEmit(event)
    }

    // ------------------------------------------------------------------
    // Internal access
    // ------------------------------------------------------------------

    /** Expose the underlying [SharedFlow] for subscribers that want unfiltered access. */
    internal val sharedFlow: SharedFlow<AppEvent> = _flow.asSharedFlow()
}
