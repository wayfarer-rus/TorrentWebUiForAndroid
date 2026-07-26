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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal enum class StartupGateAction {
    RequestNotificationPermission,
    RequestStoragePermission,
    StartDaemon
}

internal fun nextStartupGateAction(
    notificationGranted: Boolean,
    storageGranted: Boolean
): StartupGateAction = when {
    !notificationGranted -> StartupGateAction.RequestNotificationPermission
    !storageGranted -> StartupGateAction.RequestStoragePermission
    else -> StartupGateAction.StartDaemon
}

class MainActivity : ComponentActivity() {
    private val viewModel: TorrentViewModel by viewModels()
    private var daemonStartRequested = false
    private var notificationRequestLaunched = false
    private var storageSettingsLaunched = false
    private var storageSettingsPauseObserved = false

    /** Permission launcher for POST_NOTIFICATIONS (Android 13+). */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationRequestLaunched = false
        if (granted) {
            advanceStartupGate()
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
        val authManager = DefaultAuthManager(applicationContext)
        viewModel.daemonControl = DaemonControlFactory.create()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AndroidFallbackScreen(viewModel, authManager)
                }
            }
        }

        advanceStartupGate()
    }

    override fun onPause() {
        if (storageSettingsLaunched) {
            storageSettingsPauseObserved = true
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (storageSettingsLaunched && storageSettingsPauseObserved) {
            storageSettingsLaunched = false
            storageSettingsPauseObserved = false
            // Re-evaluate both gates, but do not immediately reopen settings when the user declined.
            advanceStartupGate(allowStoragePrompt = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The daemon service owns the session and WebUI lifecycle.
        // MainActivity does not stop them here; only an explicit user action (Stop downloads)
        // or system termination does that.
    }

    /** Retries the centralized startup gate from the storage fallback UI. */
    fun requestStoragePermission() = advanceStartupGate()

    /** Retries the centralized startup gate from the notification fallback UI. */
    fun requestNotificationPermission() = advanceStartupGate()

    private fun advanceStartupGate(allowStoragePrompt: Boolean = true) {
        when (nextStartupGateAction(
            notificationGranted = hasNotificationPermission(),
            storageGranted = StoragePermissionChecker.isGranted(this)
        )) {
            StartupGateAction.RequestNotificationPermission -> {
                if (!notificationRequestLaunched) {
                    notificationRequestLaunched = true
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            StartupGateAction.RequestStoragePermission -> {
                if (hasNotificationPermission() && StoragePermissionHistory.hasBeenReady(this)) {
                    daemonStartRequested = false
                    TorrentDaemon.startPermissionBlocked(applicationContext)
                }
                if (allowStoragePrompt && !storageSettingsLaunched) {
                    storageSettingsLaunched = true
                    startActivity(StoragePermissionChecker.launchPermissionSettings(this))
                }
            }
            StartupGateAction.StartDaemon -> startDaemonOnce()
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun startDaemonOnce() {
        if (!daemonStartRequested) {
            daemonStartRequested = true
            TorrentDaemon.start(applicationContext)
        }
    }
}

/**
 * M3 Android fallback screen — deliberately minimal.
 *
 * Shows daemon health and provides Start/Stop downloads controls.
 * The WebUI is the sole primary control surface for queue management, magnet addition,
 * pause/resume/remove per-torrent controls, and password changes. Android exposes only the
 * fixed local Password Reset recovery action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidFallbackScreen(viewModel: TorrentViewModel, authManager: AuthManager) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var webUiPortInput by remember { mutableStateOf(state.configuredWebUiPort.toString()) }
    LaunchedEffect(state.configuredWebUiPort) {
        webUiPortInput = state.configuredWebUiPort.toString()
    }

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
                .verticalScroll(rememberScrollState())
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

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("WebUI Port", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow("Configured", state.configuredWebUiPort.toString())
                    DiagnosticRow("Effective", state.effectiveWebUiPort?.toString() ?: "Unavailable")
                    Text(
                        "After a successful change, reconnect browsers on the effective port.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = webUiPortInput,
                        onValueChange = { webUiPortInput = it },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.configureWebUiPort(webUiPortInput) },
                        enabled = state.daemonLifecycleState != TorrentDaemon.DaemonState.Stopped.name &&
                            state.daemonLifecycleState != TorrentDaemon.DaemonState.Stopping.name,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Apply WebUI Port")
                    }
                    state.webUiPortError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            PasswordRecoveryCard(authManager)

            Spacer(modifier = Modifier.height(16.dp))

            // Session errors are distinct from an intentional stopped daemon.
            if (!state.sessionStarted && (
                state.daemonLifecycleState != TorrentDaemon.DaemonState.Stopped.name ||
                    state.diagnostics.lastError != null
                )
            ) {
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

            Spacer(modifier = Modifier.height(16.dp))

            val notificationPermissionGranted = state.notificationPermissionGranted
            if (!notificationPermissionGranted) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Notification Permission", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Allow notifications before starting the download daemon.")
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { (context as? MainActivity)?.requestNotificationPermission() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Grant Notification Permission")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            val canStartDownloads = storageState == StoragePermissionState.Ready &&
                StoragePermissionChecker.isGranted(context) && notificationPermissionGranted

            // Start/Stop downloads buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val context = LocalContext.current
                FilledTonalButton(
                    onClick = { TorrentDaemon.resume(context) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.sessionStarted && canStartDownloads
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
                           "Android provides permission setup, daemon health, recovery, and emergency Start/Stop only.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }


        }
    }
}

@Composable
internal fun PasswordRecoveryCard(authManager: AuthManager) {
    val controller = remember(authManager) { PasswordResetController(authManager) }
    var state by remember { mutableStateOf(controller.state) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Password Recovery", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Use this only when the current WebUI Password is unknown.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { state = controller.requestConfirmation() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset WebUI Password")
            }
            state.message?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (state.confirmationRequired) {
        AlertDialog(
            onDismissRequest = { state = controller.cancel() },
            title = { Text("Reset WebUI Password?") },
            text = {
                Text(
                    "This replaces the current Password with start123. " +
                        "Open browsers must authenticate again."
                )
            },
            confirmButton = {
                Button(onClick = { state = controller.confirm() }) {
                    Text("Reset Password")
                }
            },
            dismissButton = {
                TextButton(onClick = { state = controller.cancel() }) {
                    Text("Cancel")
                }
            }
        )
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
