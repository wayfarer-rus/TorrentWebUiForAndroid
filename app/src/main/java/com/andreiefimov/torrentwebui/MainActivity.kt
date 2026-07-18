package com.andreiefimov.torrentwebui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: TorrentViewModel by viewModels()

    /** Permission launcher for POST_NOTIFICATIONS (Android 13+) */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            TorrentDaemon.start(this.applicationContext)
        } else {
            Toast.makeText(
                this,
                "Notification permission is required for the daemon to run as a foreground service.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authManager = DefaultAuthManager(this.applicationContext)
        // Wire the daemon control seam: production uses TorrentSession, tests can inject mocks.
        viewModel.daemonControl = DaemonControlFactory.create()

        // Request notification permission (Android 13+) before starting daemon
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (permissionGranted) {
                TorrentDaemon.start(this.applicationContext)
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            TorrentDaemon.start(this.applicationContext)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // M3 Android fallback: only daemon health + Start/Stop downloads.
                    // The WebUI is the sole primary control surface for torrent operations.
                    AndroidFallbackScreen(viewModel, authManager)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The daemon service owns the session and WebUI lifecycle.
        // MainActivity does not stop them here; only an explicit user action (Stop downloads)
        // or system termination does that.
    }

    /**
     * Checks storage permission and launches the system settings intent if denied.
     * Called when the user taps "Grant storage permission" in the fallback UI.
     */
    fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = StoragePermissionChecker.launchPermissionSettings(this)
                startActivity(intent)
            }
        }
    }
}

/**
 * M3 Android fallback screen — deliberately minimal.
 *
 * Shows daemon health and provides Start/Stop downloads controls.
 * The WebUI is the sole primary control surface for queue management, magnet addition,
 * pause/resume/remove per-torrent controls, and password changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidFallbackScreen(viewModel: TorrentViewModel, authManager: AuthManager) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Torrent Daemon") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            val context = LocalContext.current

            // Daemon health card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Daemon Health", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow("Native loaded", if (state.diagnostics.nativeLoaded) "yes" else "no")
                    DiagnosticRow("Session started", if (state.sessionStarted) "yes" else "no")
                    DiagnosticRow("libtorrent", state.diagnostics.libtorrentVersion)
                    if (state.diagnostics.lastError != null) {
                        DiagnosticRow("Last error", state.diagnostics.lastError!!)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Session status alert
            if (!state.sessionStarted) {
                Alert(
                    type = AlertType.Error,
                    message = "Session failed to start. ${state.diagnostics.lastError ?: "Unknown error"}"
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Storage permission status card (Milestone 4)
            val storageState = state.storagePermissionState
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (storageState) {
                        StoragePermissionState.Ready -> MaterialTheme.colorScheme.primaryContainer
                        StoragePermissionState.DeniedAtStartup,
                        StoragePermissionState.RevokedRuntime -> MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Storage Permission", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow(
                        "State",
                        when (storageState) {
                            StoragePermissionState.Ready -> "Ready"
                            StoragePermissionState.DeniedAtStartup -> "Denied (startup) — grant in system settings"
                            StoragePermissionState.RevokedRuntime -> "Revoked — storage operations blocked"
                        }
                    )

                    if (storageState != StoragePermissionState.Ready) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val act = context as? MainActivity
                        Button(
                            onClick = { act?.requestStoragePermission() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant All Files Access")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Start/Stop downloads buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val context = LocalContext.current
                FilledTonalButton(
                    onClick = { TorrentDaemon.resume(context) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.sessionStarted
                ) {
                    Text("Start downloads")
                }

                OutlinedButton(
                    onClick = { TorrentDaemon.stop(context) },
                    modifier = Modifier.weight(1f),
                    enabled = state.sessionStarted
                ) {
                    Text("Stop downloads")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Info text
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = "The WebUI (accessible via LAN browser) is the primary control surface.\n\n" +
                           "Use the WebUI to add magnets, manage the queue, and pause/resume/remove torrents.\n\n" +
                           "Android provides onboarding, daemon health, and emergency Start/Stop only.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
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
