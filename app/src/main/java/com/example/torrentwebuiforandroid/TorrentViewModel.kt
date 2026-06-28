package com.example.torrentwebuiforandroid

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
        val ids = TorrentSession.getAllTorrentIds()
        val statuses = ids.mapNotNull { id ->
            TorrentSession.getTorrentStatus(id)
        }
        _uiState.value = _uiState.value.copy(
            torrents = statuses,
            diagnostics = TorrentSession.getDiagnostics()
        )
    }

    fun addMagnet(uri: String) {
        viewModelScope.launch {
            val id = TorrentSession.addMagnet(uri)
            if (id > 0) {
                _uiState.value = _uiState.value.copy(addMagnetError = null)
            } else {
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
