package com.andreiefimov.torrentwebui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: TorrentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TorrentScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentScreen(viewModel: TorrentViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var magnetUri by remember { mutableStateOf("") }
    var diagnosticsExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Torrent POC") },
                actions = {
                    IconButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Diagnostics"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Diagnostics panel
            if (diagnosticsExpanded) {
                DiagnosticsCard(state.diagnostics)
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Session status
            if (!state.sessionStarted) {
                Alert(
                    type = AlertType.Error,
                    message = "Session failed to start. ${state.diagnostics.lastError ?: "Unknown error"}"
                )
            }

            // Magnet input
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = magnetUri,
                    onValueChange = { magnetUri = it },
                    placeholder = { Text("magnet:?xt=urn:btih:...") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.addMagnet(magnetUri) },
                    enabled = magnetUri.isNotBlank() && state.sessionStarted
                ) {
                    Text("Add")
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { viewModel.addTestMagnet() },
                    enabled = state.sessionStarted
                ) {
                    Text("Test")
                }
            }

            if (state.addMagnetError != null) {
                Text(
                    text = state.addMagnetError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Torrent list
            if (state.torrents.isEmpty()) {
                Text(
                    text = "No torrents added yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.torrents, key = { it.id }) { status ->
                        TorrentCard(
                            status = status,
                            onPause = { viewModel.pauseTorrent(status.id) },
                            onResume = { viewModel.resumeTorrent(status.id) },
                            onRemove = { viewModel.removeTorrent(status.id, deleteFiles = true) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsCard(d: NativeDiagnostics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            DiagnosticRow("ABI", d.abi)
            DiagnosticRow("libtorrent", d.libtorrentVersion)
            DiagnosticRow("Native loaded", if (d.nativeLoaded) "yes" else "no")
            DiagnosticRow("Session started", if (d.sessionStarted) "yes" else "no")
            if (d.lastError != null) {
                DiagnosticRow("Last error", d.lastError)
            }
        }
    }
}

@Composable
fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TorrentCard(
    status: TorrentStatus,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = status.name.ifBlank { "(loading metadata)" },
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))

            LinearProgressIndicator(
                progress = { coalesceProgress(status.progress) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            val isPaused = status.state == "paused"
            DiagnosticRow("State", status.state)
            DiagnosticRow("Progress", "${(status.progress * 100).toInt()}%")
            DiagnosticRow("Down", formatBytes(status.downloadRate) + "/s")
            DiagnosticRow("Up", formatBytes(status.uploadRate) + "/s")
            DiagnosticRow("Peers", status.peers.toString())
            if (status.savePath.isNotBlank()) {
                DiagnosticRow("Save", status.savePath)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isPaused) {
                    FilledTonalButton(onClick = onResume) { Text("Resume") }
                } else {
                    FilledTonalButton(onClick = onPause) { Text("Pause") }
                }
                OutlinedButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

private fun coalesceProgress(p: Float): Float = p.coerceIn(0f, 1f)

private fun formatBytes(b: Long): String = when {
    b >= 1_000_000 -> String.format("%.1f MB", b / 1_000_000f)
    b >= 1_000 -> String.format("%.1f kB", b / 1_000f)
    else -> "$b B"
}

enum class AlertType { Info, Error, Warning }

@Composable
fun Alert(type: AlertType, message: String) {
    val color = when (type) {
        AlertType.Error -> MaterialTheme.colorScheme.errorContainer
        AlertType.Warning -> MaterialTheme.colorScheme.tertiaryContainer
        AlertType.Info -> MaterialTheme.colorScheme.secondaryContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
