package com.andreiefimov.torrentwebui

/** A started WebUI server instance owned through [WebUiServerController]. */
internal interface WebUiServerEngine {
    fun start()
    fun stop()
}

/** Creates one WebUI server instance for a validated port. */
internal fun interface WebUiServerEngineFactory {
    fun create(port: Int): WebUiServerEngine
}

/** Opaque capability for a server that has bound successfully but is not yet active. */
internal class WebUiServerCandidate internal constructor()

/** Outcome of making a bound candidate the active WebUI listener. */
internal enum class WebUiServerPromotionResult { Promoted, PromotedCleanupRequired }

/** Outcome of a controller-wide shutdown attempt. */
internal enum class WebUiServerStopResult { Stopped, RetryRequired }

/**
 * Owns the WebUI server lifecycle independently from the native torrent session.
 *
 * The daemon owns one controller and is therefore the only production component that starts or
 * stops the LAN WebUI.
 */
internal class WebUiServerController(
    private val engineFactory: WebUiServerEngineFactory
) {
    private val lock = Any()
    private var active: WebUiServerEngine? = null
    private var pending: PendingCandidate? = null
    private val cleanupRequired = mutableListOf<WebUiServerEngine>()

    val isRunning: Boolean
        get() = synchronized(lock) { active != null }

    fun start(port: Int) {
        synchronized(lock) {
            if (active != null) return
            active = createStartedEngine(port)
        }
    }

    fun bindCandidate(port: Int): WebUiServerCandidate = synchronized(lock) {
        check(pending == null) { "A WebUI server candidate is already bound" }
        val engine = createStartedEngine(port)
        val token = WebUiServerCandidate()
        pending = PendingCandidate(token, engine)
        token
    }

    fun promote(candidate: WebUiServerCandidate): WebUiServerPromotionResult = synchronized(lock) {
        val selected = requirePending(candidate)
        val previous = active

        // Promotion is the non-throwing commit point after durable port persistence. The candidate
        // becomes authoritative before retiring the old listener, whose cleanup remains owned here.
        active = selected.engine
        pending = null
        if (previous == null) return@synchronized WebUiServerPromotionResult.Promoted

        try {
            previous.stop()
            WebUiServerPromotionResult.Promoted
        } catch (_: Throwable) {
            cleanupRequired += previous
            WebUiServerPromotionResult.PromotedCleanupRequired
        }
    }

    fun discard(candidate: WebUiServerCandidate) {
        synchronized(lock) {
            val selected = requirePending(candidate)
            selected.engine.stop()
            pending = null
        }
    }

    /** Retries only retired-listener cleanup without disturbing the active server. */
    fun retryRetiredServers(): Boolean = synchronized(lock) {
        retryRetiredServersLocked()
    }

    fun stop(): WebUiServerStopResult = synchronized(lock) {
        var retryRequired = false

        pending?.let { selected ->
            try {
                selected.engine.stop()
                pending = null
            } catch (_: Throwable) {
                retryRequired = true
            }
        }
        active?.let { selected ->
            try {
                selected.stop()
                active = null
            } catch (_: Throwable) {
                retryRequired = true
            }
        }
        if (!retryRetiredServersLocked()) retryRequired = true

        if (retryRequired) WebUiServerStopResult.RetryRequired else WebUiServerStopResult.Stopped
    }

    private fun retryRetiredServersLocked(): Boolean {
        val retained = cleanupRequired.iterator()
        while (retained.hasNext()) {
            try {
                retained.next().stop()
                retained.remove()
            } catch (_: Throwable) {
                // Retain ownership for the next daemon-owned retry.
            }
        }
        return cleanupRequired.isEmpty()
    }

    private fun createStartedEngine(port: Int): WebUiServerEngine {
        val engine = engineFactory.create(port)
        try {
            engine.start()
        } catch (failure: Throwable) {
            try {
                engine.stop()
            } catch (cleanupFailure: Throwable) {
                cleanupRequired += engine
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        return engine
    }

    private fun requirePending(candidate: WebUiServerCandidate): PendingCandidate {
        val current = pending
        require(current?.token === candidate) { "Candidate does not belong to this controller" }
        return current
    }

    private data class PendingCandidate(
        val token: WebUiServerCandidate,
        val engine: WebUiServerEngine
    )
}
