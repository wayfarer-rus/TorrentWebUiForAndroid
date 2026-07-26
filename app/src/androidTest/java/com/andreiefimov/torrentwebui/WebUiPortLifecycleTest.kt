package com.andreiefimov.torrentwebui

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreiefimov.torrentwebui.events.AlertDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebUiPortLifecycleTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        StartupPermissionTestHelper.ensureGranted(context)
        context.getSharedPreferences(
            SharedPreferencesWebUiPortStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        RecoverySuppressionStore.clearForceStopped(context)
        context.stopService(Intent(context, TorrentDaemon::class.java))
        TorrentDaemon.currentDaemonState = TorrentDaemon.DaemonState.Stopped
        Thread.sleep(500)
    }

    @After
    fun tearDown() {
        TorrentDaemon.stop(context)
        waitUntil { TorrentDaemon.getHealthStatus(context).lifecycleState == TorrentDaemon.DaemonState.Stopped.name }
        TorrentDaemon.resetDaemonControlFactory()
        AlertDispatcher.stop()
        context.getSharedPreferences(
            SharedPreferencesWebUiPortStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    @Test
    fun successfulPortSwitchDoesNotRestartOrDestroyTransferSession() {
        var initCalls = 0
        var destroyCalls = 0
        val recordingControl = object : DaemonControl by RecoveryBlockedDaemonControl {
            override val lastError: String? = null

            override fun init(context: Context): Boolean {
                initCalls += 1
                return true
            }

            override fun destroy(): Boolean {
                destroyCalls += 1
                return true
            }

            override fun getDiagnostics(): NativeDiagnostics = NativeDiagnostics(
                abi = "test",
                libtorrentVersion = "test",
                nativeLoaded = true,
                sessionStarted = true,
                lastError = null
            )
        }
        TorrentDaemon.daemonControlFactory = { recordingControl }

        TorrentDaemon.start(context)
        waitUntil { TorrentDaemon.getWebUiPortStatus(context).effectivePort == WebUiPort.DEFAULT }

        TorrentDaemon.configureWebUiPort(context, "18081")
        waitUntil { TorrentDaemon.getWebUiPortStatus(context).effectivePort == 18081 }

        val switched = TorrentDaemon.getWebUiPortStatus(context)
        assertEquals(18081, switched.configuredPort)
        assertEquals(18081, switched.effectivePort)
        assertNull(switched.operationError)
        assertEquals(1, initCalls)
        assertEquals(0, destroyCalls)
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Condition was not met within ${timeoutMs}ms")
    }
}
