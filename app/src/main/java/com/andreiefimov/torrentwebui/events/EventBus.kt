package com.andreiefimov.torrentwebui.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Lightweight publish/subscribe event bus for decoupling the native engine from UI.
 *
 * Events are typed via sealed hierarchies ([TorrentEvent], [SessionEvent]) so consumers
 * can pattern-match on exactly the events they care about.
 *
 * Thread-safety: [post] is safe to call from any thread (libtorrent alert callbacks,
 * IO dispatcher coroutines, etc.). Subscribers receive events on the context they
 * observe with.
 */
object EventBus {

    private val _flow = MutableSharedFlow<AppEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Observe all events. Call [kotlinx.coroutines.flow.Flow.collect] in a coroutine. */
    fun observe(): Flow<AppEvent> = _flow.asSharedFlow()

    /**
     * Post an event to all current and future subscribers.
     * Safe to call from any thread — the SharedFlow handles synchronization.
     */
    fun post(event: AppEvent) {
        _flow.tryEmit(event)
    }
}
