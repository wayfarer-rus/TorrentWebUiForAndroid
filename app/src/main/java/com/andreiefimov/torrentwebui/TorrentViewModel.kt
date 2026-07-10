package com.andreiefimov.torrentwebui

import android.util.Log
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel that owns the libtorrent session lifecycle and polls for status.
 * Preserved across configuration changes (screen rotation).
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

    private var pollingJob: kotlinx.coroutines.Job? = null

    init {
        startSession()
    }

    private fun startSession() {
        val ok = TorrentSession.init(getApplication())
        _uiState.value = _uiState.value.copy(
            diagnostics = TorrentSession.getDiagnostics(),
            sessionStarted = ok
        )
        if (ok) {
            startPolling()
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
        TorrentSession.popAlerts()
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
