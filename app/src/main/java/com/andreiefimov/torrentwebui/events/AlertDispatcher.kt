package com.andreiefimov.torrentwebui.events

import android.util.Log
import com.andreiefimov.torrentwebui.TorrentSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Background coroutine that polls native libtorrent alerts and dispatches typed events.
 *
 * Runs on [Dispatchers.IO] with a 2-second poll interval. Each alert from the native
 * layer is categorized and posted to [EventBus] as either a [TorrentEvent] or
 * [SessionEvent].
 *
 * Lifecycle: started when the session initializes, stopped when destroyed.
 */
object AlertDispatcher {

    private const val TAG = "AlertDispatcher"
    private const val POLL_INTERVAL_MS = 2000L

    @Volatile private var job: Job? = null
    @Volatile private var running = false

    /** Starts polling alerts. Safe to call multiple times (no-op if already running). */
    fun start() {
        if (running) return
        running = true
        job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            Log.d(TAG, "AlertDispatcher started")
            while (true) {
                try {
                    processAlerts()
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing alerts", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Stops polling. */
    fun stop() {
        running = false
        job?.cancel()
        job = null
        Log.d(TAG, "AlertDispatcher stopped")
    }

    private fun processAlerts() {
        val json = TorrentSession.popAlerts()
        if (json == "[]" || json.isBlank()) return

        val alerts = parseAlerts(json)
        if (alerts.isEmpty()) return

        Log.d(TAG, "Processing ${alerts.size} alert(s)")
        val hashMap = TorrentSession.getAllTorrentHashes()

        for (alert in alerts) {
            val torrentId = resolveTorrentId(alert.hash, hashMap)

            when (alert.type) {
                "error_alert" -> {
                    if (torrentId > 0) {
                        EventBus.postTorrentEvent(TorrentEvent.Error(torrentId, alert.message))
                    } else {
                        EventBus.postSessionEvent(SessionEvent.Error(alert.message))
                    }
                }
                "state_changed_alert" -> {
                    if (torrentId > 0) {
                        EventBus.postTorrentEvent(TorrentEvent.StateChanged(torrentId, alert.category ?: "unknown"))
                    }
                }
                "torrent_added_alert" -> {
                    if (torrentId > 0) {
                        EventBus.postTorrentEvent(TorrentEvent.Added(torrentId))
                    }
                }
                "torrent_removed_alert" -> {
                    if (torrentId > 0) {
                        EventBus.postTorrentEvent(TorrentEvent.Removed(torrentId))
                    }
                }
                "tracker_warning_alert",
                "listen_failed_alert" -> {
                    EventBus.postSessionEvent(SessionEvent.Warning(alert.message))
                }
                else -> {
                    // Unknown alert type — log for debugging but don't post.
                    Log.d(TAG, "Unhandled alert type: ${alert.type}")
                }
            }
        }
    }

    private fun resolveTorrentId(hash: String, hashMap: Map<Long, String>): Long {
        if (hash.isBlank()) return -1L
        // Normalize: ensure hex format for lookup.
        val normalizedHash = if (hash.length == 40) hash else hash.take(40)
        return hashMap.entries.find { it.value.equals(normalizedHash, ignoreCase = true) }?.key ?: -1L
    }

    private data class Alert(
        val type: String,
        val message: String,
        val category: String?,
        val hash: String,
    )

    private fun parseAlerts(json: String): List<Alert> {
        val alerts = mutableListOf<Alert>()
        // Simple JSON array parser: split on alert objects.
        if (!json.startsWith("[")) return alerts

        var depth = 0
        var inString = false
        var escape = false
        var start = -1

        for (i in json.indices) {
            val c = json[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val obj = json.substring(start, i + 1)
                        alerts.add(parseAlertObject(obj))
                        start = -1
                    }
                }
            }
        }
        return alerts
    }

    private fun parseAlertObject(obj: String): Alert {
        fun extract(field: String): String? {
            val pattern = """"$field"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)""""
            val match = pattern.toRegex().find(obj) ?: return null
            // Unescape JSON string.
            return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
        }

        return Alert(
            type = extract("type") ?: "unknown",
            message = extract("message") ?: "",
            category = extract("category"),
            hash = extract("info_hash") ?: extract("hash") ?: "",
        )
    }

    val isRunning get() = running
}
