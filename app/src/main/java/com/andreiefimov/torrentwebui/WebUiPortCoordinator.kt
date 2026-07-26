package com.andreiefimov.torrentwebui

import android.content.Context

/** Consumer-safe WebUI port policy shared by daemon orchestration and Android presentation. */
internal object WebUiPort {
    const val DEFAULT = 8080
    const val MIN = 1024
    const val MAX = 65535
    const val INVALID_PORT_ERROR = "Enter a WebUI port from 1024 to 65535."
    const val OLD_SERVER_CLEANUP_ERROR =
        "WebUI moved to the new port, but the previous listener still needs cleanup."

    fun isValid(port: Int): Boolean = port in MIN..MAX

    fun parse(input: String): Int? = input.trim().toIntOrNull()?.takeIf(::isValid)
}

/** Durable configured-port seam. Writes report whether the value reached durable storage. */
internal interface WebUiPortStore {
    fun read(): Int
    fun write(port: Int): Boolean
}

/** Synchronous SharedPreferences adapter because a failed write must roll back the candidate bind. */
internal class SharedPreferencesWebUiPortStore(context: Context) : WebUiPortStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun read(): Int = preferences.getInt(KEY_CONFIGURED_PORT, WebUiPort.DEFAULT)
        .takeIf(WebUiPort::isValid)
        ?: WebUiPort.DEFAULT

    override fun write(port: Int): Boolean {
        require(WebUiPort.isValid(port)) { "WebUI port is outside the accepted range" }
        return preferences.edit().putInt(KEY_CONFIGURED_PORT, port).commit()
    }

    internal companion object {
        const val PREFERENCES_NAME = "webui_port_prefs"
        const val KEY_CONFIGURED_PORT = "configured_port"
    }
}

/** Android-visible state. A null effective port means no WebUI listener is currently available. */
internal data class WebUiPortStatus(
    val configuredPort: Int = WebUiPort.DEFAULT,
    val effectivePort: Int? = null,
    val operationError: String? = null
)

/**
 * Owns the bind -> persist -> promote transaction without touching native torrent-session state.
 */
internal class WebUiPortCoordinator(
    private val servers: WebUiServerController,
    private val store: WebUiPortStore
) {
    private val lock = Any()
    private var current = WebUiPortStatus(configuredPort = store.read())

    val status: WebUiPortStatus
        get() = synchronized(lock) { current }

    fun startConfigured(): WebUiPortStatus = synchronized(lock) {
        if (servers.isRunning && current.effectivePort != null) return current

        val configuredPort = store.read()
        current = try {
            servers.start(configuredPort)
            WebUiPortStatus(configuredPort, configuredPort)
        } catch (_: Exception) {
            WebUiPortStatus(
                configuredPort = configuredPort,
                effectivePort = null,
                operationError = "WebUI could not start on configured port $configuredPort."
            )
        }
        current
    }

    fun apply(input: String): WebUiPortStatus = synchronized(lock) {
        val requestedPort = WebUiPort.parse(input)
            ?: return updateError(WebUiPort.INVALID_PORT_ERROR)

        if (current.effectivePort == requestedPort && current.configuredPort == requestedPort) {
            current = current.copy(operationError = null)
            return current
        }

        val candidate = try {
            servers.bindCandidate(requestedPort)
        } catch (_: Exception) {
            return updateError(unavailableMessage(requestedPort))
        }

        val previousConfiguredPort = current.configuredPort
        val persisted = try {
            store.write(requestedPort)
        } catch (_: Exception) {
            false
        }
        if (!persisted) {
            restoreConfiguredPort(previousConfiguredPort)
            discardAfterFailure(candidate)
            return updateError(persistenceFailureMessage())
        }

        val promotion = servers.promote(candidate)
        current = WebUiPortStatus(
            configuredPort = requestedPort,
            effectivePort = requestedPort,
            operationError = when (promotion) {
                WebUiServerPromotionResult.Promoted -> null
                WebUiServerPromotionResult.PromotedCleanupRequired -> WebUiPort.OLD_SERVER_CLEANUP_ERROR
            }
        )
        current
    }

    fun retryRetiredServers(): WebUiPortStatus = synchronized(lock) {
        if (servers.retryRetiredServers() && current.operationError == WebUiPort.OLD_SERVER_CLEANUP_ERROR) {
            current = current.copy(operationError = null)
        }
        current
    }

    fun stop(): WebUiServerStopResult = synchronized(lock) {
        val result = servers.stop()
        if (result == WebUiServerStopResult.Stopped) {
            current = current.copy(effectivePort = null)
        }
        result
    }

    private fun restoreConfiguredPort(previousPort: Int) {
        try {
            // SharedPreferences changes its process-local value before commit() reports failure.
            // Rewriting the old port restores both the current process view and, when possible, disk.
            store.write(previousPort)
        } catch (_: Exception) {
            // A thrown write has not provided evidence that the previous durable value changed.
        }
    }

    private fun discardAfterFailure(candidate: WebUiServerCandidate) {
        repeat(2) {
            try {
                servers.discard(candidate)
                return
            } catch (_: Exception) {
                // The controller retains candidate ownership, so one immediate retry is safe.
            }
        }
        // If both attempts fail, controller-wide shutdown still owns the remaining cleanup.
    }

    private fun updateError(message: String): WebUiPortStatus {
        current = current.copy(operationError = message)
        return current
    }

    private fun unavailableMessage(port: Int): String = current.effectivePort?.let {
        "Port $port is unavailable. WebUI remains on port $it."
    } ?: "WebUI could not start on port $port."

    private fun persistenceFailureMessage(): String = current.effectivePort?.let {
        "Could not save WebUI port. WebUI remains on port $it."
    } ?: "Could not save WebUI port."
}
