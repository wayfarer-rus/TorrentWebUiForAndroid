package com.andreiefimov.torrentwebui.events

/**
 * Root interface for all events published on [EventBus].
 *
 * Both [TorrentEvent] and [SessionEvent] extend this, so subscribers can observe
 * the full event stream with a single Flow.
 */
interface AppEvent
