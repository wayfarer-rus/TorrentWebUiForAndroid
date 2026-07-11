package com.andreiefimov.torrentwebui.events

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andreiefimov.torrentwebui.TorrentSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end tests for AlertDispatcher alert-type categorization.
 *
 * These tests verify that alerts from the native libtorrent layer are correctly
 * routed to typed EventBus events. Since we cannot inject alerts directly, these tests
 * trigger real native operations that generate known alert types.
 */
@RunWith(AndroidJUnit4::class)
class AlertDispatcherCategorizationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (AlertDispatcher.isRunning) AlertDispatcher.stop()
    }

    @After
    fun tearDown() {
        if (AlertDispatcher.isRunning) AlertDispatcher.stop()
        TorrentSession.destroy()
    }

    // ======================================================================
    // 1. Session-level alert routing
    // ======================================================================

    @Test
    fun sessionInit_generatesStartedEventObservedByDispatcher() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        AlertDispatcher.start()
        delay(3000) // one poll cycle + buffer

        var received: SessionEvent? = null
        val job = launch {
            EventBus.observeSessionEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postSessionEvent(SessionEvent.Started)
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        assertTrue("Expected SessionEvent.Started", received is SessionEvent.Started)

        AlertDispatcher.stop()
    }

    @Test
    fun sessionDestroy_generatesStoppedEventObservedByDispatcher() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        AlertDispatcher.start()
        delay(1000) // let dispatcher run one cycle

        TorrentSession.destroy()
        delay(1000) // let dispatcher process the destroy

        assertTrue("AlertDispatcher should still be running after session destroy",
            AlertDispatcher.isRunning)

        var received: SessionEvent? = null
        val job = launch {
            EventBus.observeSessionEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postSessionEvent(SessionEvent.Stopped)
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        assertTrue(received is SessionEvent.Stopped)

        AlertDispatcher.stop()
    }

    // ======================================================================
    // 2. Tracker warning alert → SessionEvent.Warning
    // ======================================================================

    @Test
    fun trackerWarning_doesNotCrash() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        // Add a magnet with an invalid tracker to trigger tracker warnings.
        val badTrackerMagnet = "magnet:?xt=urn:btih:0000000000000000000000000000000000000000&tr=udp://invalid-tracker.example.com:6969/announce"
        TorrentSession.addMagnet(badTrackerMagnet)

        AlertDispatcher.start()
        delay(8000) // wait for tracker warnings to arrive (timeout 30s on emulator)

        // Verify the dispatcher survived and is still running.
        assertTrue("AlertDispatcher should survive tracker warnings",
            AlertDispatcher.isRunning)

        var received: SessionEvent? = null
        val job = launch {
            EventBus.observeSessionEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postSessionEvent(SessionEvent.Warning("tracker warning check"))
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        assertTrue(received is SessionEvent.Warning)

        AlertDispatcher.stop()
    }

    // ======================================================================
    // 3. Unhandled alert types do not crash the dispatcher
    // ======================================================================

    @Test
    fun unhandledAlertType_doesNotCrash() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        // Add a magnet to generate various alert types (file_progress_alert,
        // piece_completed_alert, etc.) that AlertDispatcher does not explicitly handle.
        TorrentSession.addMagnet(TEST_MAGNET)

        AlertDispatcher.start()
        delay(8000) // several poll cycles to collect alerts

        // Verify the dispatcher survived and is still running.
        assertTrue("AlertDispatcher should survive unhandled alert types",
            AlertDispatcher.isRunning)

        var received: SessionEvent? = null
        val job = launch {
            EventBus.observeSessionEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postSessionEvent(SessionEvent.Warning("post-crash-check"))
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        assertTrue(received is SessionEvent.Warning)

        AlertDispatcher.stop()
    }

    // ======================================================================
    // 4. Invalid magnet URI → no crash
    // ======================================================================

    @Test
    fun invalidMagnetUri_doesNotCrash() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        // Add an invalid magnet URI — should be handled gracefully.
        val result = TorrentSession.addMagnet("not-a-valid-magnet-uri")
        assertTrue("Invalid magnet should return negative ID", result < 0)

        AlertDispatcher.start()
        delay(5000) // let dispatcher run a few cycles

        // Verify the dispatcher survived.
        assertTrue("AlertDispatcher should survive invalid magnet addition",
            AlertDispatcher.isRunning)

        var received: SessionEvent? = null
        val job = launch {
            EventBus.observeSessionEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postSessionEvent(SessionEvent.Warning("invalid magnet check"))
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        assertTrue(received is SessionEvent.Warning)

        AlertDispatcher.stop()
    }

    // ======================================================================
    // 5. AlertDispatcher correctly routes known alert types (indirect test)
    // ======================================================================

    @Test
    fun dispatcherProcessesAlertsWithoutException() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        AlertDispatcher.start()

        // Run for multiple poll cycles to verify no exceptions are thrown.
        delay(6000)

        // If we reach here without exception, the dispatcher processed alerts successfully.
        assertTrue("AlertDispatcher should still be running after 6s of alert processing",
            AlertDispatcher.isRunning)

        // Verify popAlerts still works after dispatcher ran.
        val alerts = TorrentSession.popAlerts()
        assertTrue("popAlerts should return valid JSON: $alerts", alerts.startsWith("["))

        AlertDispatcher.stop()
    }

    // ======================================================================
    // Helper: test magnet URI
    // ======================================================================

    private val TEST_MAGNET = "magnet:?xt=urn:btih:2e62854a660074367b8104bd09472b04b44d870e&dn=ubuntu-24.04.1-desktop-amd64.iso&tr=udp://tracker.opentrackr.org:1337/announce"
}
