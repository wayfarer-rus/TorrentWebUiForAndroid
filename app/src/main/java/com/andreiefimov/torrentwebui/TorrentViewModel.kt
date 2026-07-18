package com.andreiefimov.torrentwebui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andreiefimov.torrentwebui.events.AlertDispatcher
import com.andreiefimov.torrentwebui.events.AlertEvent
import com.andreiefimov.torrentwebui.events.EventBus
import com.andreiefimov.torrentwebui.events.SessionEvent
import com.andreiefimov.torrentwebui.events.TorrentEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for Android fallback state, preserved across configuration changes.
 *
 * The foreground daemon owns the native-session lifecycle. This ViewModel observes daemon health
 * and delegates controls through [DaemonControl] without initializing the native session itself.
 */
class TorrentViewModel(application: Application) : AndroidViewModel(application) {

    // Hardcoded test magnet for Stage 1 acceptance testing.
    // Ubuntu 24.04 LTS Desktop ISO (legal, public domain).
    private val TEST_MAGNET = "magnet:?xt=urn:btih:2e62854a660074367b8104bd09472b04b44d870e&dn=ubuntu-24.04.1-desktop-amd64.iso&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.openbittorrent.com:6969/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://open.demonii.com:1337/announce"

    /**
     * Daemon control seam — defaults to production [TorrentSession], injectable for tests.
     *
     * This is the unified seam that provides both session operations and lifecycle management.
     * Future milestones swap in a foreground-service-backed implementation without changing callers.
     */
    @Volatile
    var daemonControl: DaemonControl = TorrentSession

    fun addTestMagnet() {
        addMagnet(TEST_MAGNET)
    }

    private val _uiState = MutableStateFlow(TorrentUiState())
    val uiState: StateFlow<TorrentUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var eventSubscription: Job? = null

    init {
        startHealthPolling()
    }

    private fun startHealthPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                refreshDaemonHealth()
                delay(1000L)
            }
        }
    }

    private fun refreshDaemonHealth() {
        val health = TorrentDaemon.getHealthStatus(getApplication())
        val diagnostics = daemonControl.getDiagnostics()
        val sessionStarted = health.lifecycleState in setOf(
            TorrentDaemon.DaemonState.Starting.name,
            TorrentDaemon.DaemonState.Running.name,
            TorrentDaemon.DaemonState.RecoveryBlocked.name
        )
        _uiState.value = _uiState.value.copy(
            diagnostics = diagnostics,
            sessionStarted = sessionStarted,
            storagePermissionState = daemonControl.storagePermissionState
        )
    }

    private fun refreshTorrents() {
        // Alert processing is now handled by AlertDispatcher + EventBus.
        val ids = daemonControl.getAllTorrentIds()
        val statuses = ids.mapNotNull { id ->
            daemonControl.getTorrentStatus(id)
        }
        if (statuses.isNotEmpty()) {
            Log.d("TorrentViewModel", "Poll: ${statuses.size} torrents, first=${statuses[0].state} progress=${(statuses[0].progress * 100).toInt()}%")
        }
        _uiState.value = _uiState.value.copy(
            torrents = statuses,
            diagnostics = daemonControl.getDiagnostics()
        )
    }

    private fun subscribeToEvents() {
        eventSubscription?.cancel()
        eventSubscription = viewModelScope.launch {
            val sessionJob = launch {
                EventBus.observeSessionEvents().collect { event ->
                    when (event) {
                        is SessionEvent.Started -> {
                            _uiState.value = _uiState.value.copy(
                                sessionStarted = true,
                                diagnostics = daemonControl.getDiagnostics()
                            )
                        }
                        is SessionEvent.Error -> {
                            _uiState.value = _uiState.value.copy(
                                sessionStarted = false,
                                diagnostics = daemonControl.getDiagnostics()
                            )
                        }
                        is SessionEvent.Warning -> {
                            Log.w("TorrentViewModel", "Session warning: ${event.message}")
                        }
                        is SessionEvent.Stopped -> {
                            _uiState.value = _uiState.value.copy(
                                sessionStarted = false,
                                diagnostics = daemonControl.getDiagnostics()
                            )
                        }
                    }
                }
            }
            val torrentJob = launch {
                EventBus.observeTorrentEvents().collect { event ->
                    when (event) {
                        is TorrentEvent.StateChanged -> {
                            // Force a refresh to pick up the new state.
                            refreshTorrents()
                        }
                        is TorrentEvent.Added -> {
                            // Force a refresh to show the newly added torrent.
                            refreshTorrents()
                        }
                        is TorrentEvent.Removed -> {
                            // Force a refresh to remove the torrent from the list.
                            refreshTorrents()
                        }
                        is TorrentEvent.Error -> {
                            Log.e("TorrentViewModel", "Torrent error #$${event.torrentId}: ${event.message}")
                            refreshTorrents()
                        }
                        is TorrentEvent.DestinationUnavailable -> {
                            Log.w("TorrentViewModel", "Destination unavailable for torrent #${event.torrentId}: ${event.path}")
                            refreshTorrents()
                        }
                        is TorrentEvent.MoveInterrupted -> {
                            Log.w("TorrentViewModel", "Move interrupted for torrent #${event.torrentId}: ${event.sourcePath} → ${event.targetPath}")
                            refreshTorrents()
                        }
                    }
                }
            }
            val alertJob = launch {
                EventBus.observeAlerts().collect { event ->
                    // Keep the last 50 alerts in memory for UI display.
                    val current = _uiState.value.recentAlerts.toMutableList()
                    current.add(event)
                    if (current.size > MAX_ALERTS) {
                        current.removeAt(0)
                    }
                    _uiState.value = _uiState.value.copy(recentAlerts = current)
                }
            }
            // Keep all subscriptions alive until this scope is cancelled.
            joinAll(sessionJob, torrentJob, alertJob)
        }
    }

    companion object {
        /** Maximum number of recent alerts kept in memory. */
        private const val MAX_ALERTS = 50
    }

    fun addMagnet(uri: String) {
        Log.d("TorrentViewModel", "Adding magnet: ${uri.take(60)}...")
        viewModelScope.launch {
            val id = daemonControl.addMagnet(uri)
            if (id > 0) {
                Log.d("TorrentViewModel", "Magnet added, id=$id")
                _uiState.value = _uiState.value.copy(addMagnetError = null)
            } else {
                Log.e("TorrentViewModel", "Failed to add magnet: ${daemonControl.lastError}")
                _uiState.value = _uiState.value.copy(
                    addMagnetError = daemonControl.lastError
                )
            }
        }
    }

    fun pauseTorrent(id: Long) {
        daemonControl.pauseTorrent(id)
    }

    fun resumeTorrent(id: Long) {
        daemonControl.resumeTorrent(id)
    }

    fun removeTorrent(id: Long, deleteFiles: Boolean) {
        daemonControl.removeTorrent(id, deleteFiles)
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        eventSubscription?.cancel()
        AlertDispatcher.stop()
        daemonControl.destroy()
    }
}

/**
 * UI state held by the ViewModel.
 */
data class TorrentUiState(
    val torrents: List<TorrentStatus> = emptyList(),
    val diagnostics: NativeDiagnostics = NativeDiagnostics(
        abi = "unknown",
        libtorrentVersion = "unknown",
        nativeLoaded = false,
        sessionStarted = false,
        lastError = null
    ),
    val sessionStarted: Boolean = false,
    val addMagnetError: String? = null,
    val recentAlerts: List<AlertEvent> = emptyList(),
    val storagePermissionState: StoragePermissionState = StoragePermissionState.Ready
)
