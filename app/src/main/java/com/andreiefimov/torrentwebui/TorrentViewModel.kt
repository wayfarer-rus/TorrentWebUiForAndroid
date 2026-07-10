package com.andreiefimov.torrentwebui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.andreiefimov.torrentwebui.events.AlertDispatcher
import com.andreiefimov.torrentwebui.events.EventBus
import com.andreiefimov.torrentwebui.events.SessionEvent
import com.andreiefimov.torrentwebui.events.TorrentEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel that owns the libtorrent session lifecycle and polls for status.
 * Preserved across configuration changes (screen rotation).
 *
 * Alert processing is delegated to [AlertDispatcher], which polls native alerts
 * and dispatches typed events via [EventBus]. The ViewModel observes those events
 * to stay in sync with the UI state.
 */
class TorrentViewModel(application: Application) : AndroidViewModel(application) {

    // Hardcoded test magnet for Stage 1 acceptance testing.
    // Ubuntu 24.04 LTS Desktop ISO (legal, public domain).
    private val TEST_MAGNET = "magnet:?xt=urn:btih:2e62854a660074367b8104bd09472b04b44d870e&dn=ubuntu-24.04.1-desktop-amd64.iso&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce&tr=udp://tracker.torrent.eu.org:451/announce&tr=udp://tracker.openbittorrent.com:6969/announce&tr=udp://exodus.desync.com:6969/announce&tr=udp://open.demonii.com:1337/announce"

    fun addTestMagnet() {
        addMagnet(TEST_MAGNET)
    }

    private val _uiState = MutableStateFlow(TorrentUiState())
    val uiState: StateFlow<TorrentUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var eventSubscription: Job? = null

    init {
        startSession()
    }

    private fun startSession() {
        AlertDispatcher.start()
        val ok = TorrentSession.init(getApplication())
        _uiState.value = _uiState.value.copy(
            diagnostics = TorrentSession.getDiagnostics(),
            sessionStarted = ok
        )
        if (ok) {
            startPolling()
            subscribeToEvents()
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                refreshTorrents()
                delay(1000L) // poll at most once per second
            }
        }
    }

    private fun refreshTorrents() {
        // Alert processing is now handled by AlertDispatcher + EventBus.
        val ids = TorrentSession.getAllTorrentIds()
        val statuses = ids.mapNotNull { id ->
            TorrentSession.getTorrentStatus(id)
        }
        if (statuses.isNotEmpty()) {
            Log.d("TorrentViewModel", "Poll: ${statuses.size} torrents, first=${statuses[0].state} progress=${(statuses[0].progress * 100).toInt()}%")
        }
        _uiState.value = _uiState.value.copy(
            torrents = statuses,
            diagnostics = TorrentSession.getDiagnostics()
        )
    }

    private fun subscribeToEvents() {
        eventSubscription?.cancel()
        eventSubscription = viewModelScope.launch {
            EventBus.observe().collect { event ->
                when (event) {
                    is SessionEvent.Started -> {
                        _uiState.value = _uiState.value.copy(
                            sessionStarted = true,
                            diagnostics = TorrentSession.getDiagnostics()
                        )
                    }
                    is SessionEvent.Error -> {
                        _uiState.value = _uiState.value.copy(
                            sessionStarted = false,
                            diagnostics = TorrentSession.getDiagnostics()
                        )
                    }
                    is SessionEvent.Warning -> {
                        Log.w("TorrentViewModel", "Session warning: ${event.message}")
                    }
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
                }
            }
        }
    }

    fun addMagnet(uri: String) {
        Log.d("TorrentViewModel", "Adding magnet: ${uri.take(60)}...")
        viewModelScope.launch {
            val id = TorrentSession.addMagnet(uri)
            if (id > 0) {
                Log.d("TorrentViewModel", "Magnet added, id=$id")
                _uiState.value = _uiState.value.copy(addMagnetError = null)
            } else {
                Log.e("TorrentViewModel", "Failed to add magnet: ${TorrentSession.lastError}")
                _uiState.value = _uiState.value.copy(
                    addMagnetError = TorrentSession.lastError
                )
            }
        }
    }

    fun pauseTorrent(id: Long) {
        TorrentSession.pauseTorrent(id)
    }

    fun resumeTorrent(id: Long) {
        TorrentSession.resumeTorrent(id)
    }

    fun removeTorrent(id: Long, deleteFiles: Boolean) {
        TorrentSession.removeTorrent(id, deleteFiles)
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        eventSubscription?.cancel()
        AlertDispatcher.stop()
        TorrentSession.destroy()
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
    val addMagnetError: String? = null
)
