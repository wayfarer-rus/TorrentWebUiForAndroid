package com.andreiefimov.torrentwebui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.app.Service.STOP_FOREGROUND_REMOVE
import androidx.core.app.NotificationCompat
import com.andreiefimov.torrentwebui.events.AlertDispatcher

/**
 * Foreground service that owns the native torrent session and WebUI server.
 *
 * The daemon survives MainActivity backgrounding and keeps the authenticated LAN WebUI available.
 * It stays active after the transfer queue becomes idle, so the LAN WebUI remains reachable;
 * only an explicit user **Stop downloads** action stops it.
 *
 * Notification permission (Android 13+) is required before the daemon can start as a foreground service.
 */
class TorrentDaemon : Service() {

    companion object {
        const val TAG = "TorrentDaemon"
        const val CHANNEL_ID = "torrent_daemon_channel"
        const val NOTIFICATION_ID = 1001

        /** Action to start the daemon. */
        const val ACTION_START = "com.andreiefimov.torrentwebui.START"

        /** Action to stop the daemon (from notification or external caller). */
        const val ACTION_STOP = "com.andreiefimov.torrentwebui.STOP"

        /** Intent extra: whether to start the daemon on boot. */
        const val EXTRA_START_ON_BOOT = "start_on_boot"

        /** Starts the daemon service with the given context. */
        fun start(context: Context) {
            val intent = Intent(context, TorrentDaemon::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stops the daemon service. */
        fun stop(context: Context) {
            val intent = Intent(context, TorrentDaemon::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** Checks if the daemon is currently running. */
        fun isRunning(context: Context): Boolean {
            // This is a simplified check; in production, you'd track service state.
            return false
        }
    }

    private var daemonControl: DaemonControl? = null
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDaemon()
            ACTION_STOP -> stopDaemon()
            Intent.ACTION_BOOT_COMPLETED -> {
                // Future: handle boot recovery (outside M3 scope)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startDaemon() {
        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkNotificationPermission()) {
                startForegroundService()
            } else {
                // Show remediation UI (simplified for M3)
                android.util.Log.w(TAG, "Notification permission denied; daemon cannot start as foreground service")
            }
        } else {
            startForegroundService()
        }
    }

    private fun startForegroundService() {
        // Initialize the daemon control (currently wraps TorrentSession)
        val control = DaemonControlFactory.create()
        if (!control.init(applicationContext)) {
            android.util.Log.e(TAG, "Failed to initialize daemon control")
            stopSelf()
            return
        }
        this.daemonControl = control

        // Start the Ktor WebUI server
        TorrentServer.start(applicationContext)

        // Start as foreground service with notification
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        android.util.Log.i(TAG, "Daemon started successfully")
    }

    private fun stopDaemon() {
        android.util.Log.i(TAG, "Stopping daemon")

        // Stop the WebUI server
        TorrentServer.stop()

        // Destroy the native session
        daemonControl?.destroy()
        daemonControl = null

        // Stop foreground service and remove notification
        @Suppress("DEPRECATION")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        android.util.Log.i(TAG, "Daemon stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Torrent Daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows torrent download status"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Create an intent to open the app when notification is tapped
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Create an intent to stop the daemon (from notification action)
        val stopIntent = Intent(this, TorrentDaemon::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Torrent Daemon")
            .setContentText("Downloading...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop downloads",
                stopPendingIntent
            )
            .build()
    }

    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true // Permission not required before Android 13
        }
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources
        TorrentServer.stop()
        daemonControl?.destroy()
    }
}
