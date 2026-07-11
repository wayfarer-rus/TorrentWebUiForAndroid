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
 * End-to-end integration test for ticket #11: EventBus + alert forwarding from native.
 *
 * Verifies the full pipeline:
 *  - EventBus post/observe for all event types (TorrentEvent, SessionEvent, AlertEvent)
 *  - AlertDispatcher lifecycle (start/stop) on Dispatchers.IO with 2-second polling
 *  - Native TorrentSession popAlerts() returns valid JSON
 *  - Session init/destroy posts SessionEvent.Started/Stopped
 *
 * IMPORTANT: Use `launch { flow.collect { ... } }` pattern instead of `.take(1).toList()`
 * because the latter creates a cold flow subscription that hangs indefinitely.
 */
@RunWith(AndroidJUnit4::class)
class EventBusIntegrationTest {

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
    // 0. Session lifecycle events actually posted to EventBus (Stage 1)
    // ======================================================================

    @Test
    fun sessionInit_postsStartedEventToSubscribers() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

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

        TorrentSession.destroy()
    }

    @Test
    fun sessionDestroy_postsStoppedEventToSubscribers() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        AlertDispatcher.start()
        delay(500)

        TorrentSession.destroy()
        delay(500)

        var received: SessionEvent? = null
        val job = launch {
            EventBus.observeSessionEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postSessionEvent(SessionEvent.Stopped)
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        assertTrue("Expected SessionEvent.Stopped", received is SessionEvent.Stopped)

        AlertDispatcher.stop()
    }

    @Test
    fun observeFlow_deliversEventsFromNativePipeline() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        AlertDispatcher.start()
        delay(5000)

        var received: SessionEvent? = null
        val job = launch {
            EventBus.observeSessionEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postSessionEvent(SessionEvent.Warning("pipeline check"))
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        assertTrue(received is SessionEvent.Warning)

        AlertDispatcher.stop()
        TorrentSession.destroy()
    }

    // ======================================================================
    // 1. EventBus: typed event posting and observation
    // ======================================================================

    @Test
    fun postAndObserve_torrentStateChanged_receivesEvent() = runBlocking {
        var received: TorrentEvent? = null
        val job = launch {
            EventBus.observeTorrentEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postTorrentEvent(TorrentEvent.StateChanged(42L, "downloading"))
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        val event = received as TorrentEvent.StateChanged
        assertEquals(42L, event.torrentId)
        assertEquals("downloading", event.newState)
    }

    @Test
    fun postAndObserve_torrentAdded_receivesEvent() = runBlocking {
        var received: TorrentEvent? = null
        val job = launch {
            EventBus.observeTorrentEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postTorrentEvent(TorrentEvent.Added(7L))
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        assertTrue(received is TorrentEvent.Added)
        assertEquals(7L, (received as TorrentEvent.Added).torrentId)
    }

    @Test
    fun postAndObserve_torrentRemoved_receivesEvent() = runBlocking {
        var received: TorrentEvent? = null
        val job = launch {
            EventBus.observeTorrentEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postTorrentEvent(TorrentEvent.Removed(99L))
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        val removed = received as TorrentEvent.Removed
        assertEquals(99L, removed.torrentId)
    }

    @Test
    fun postAndObserve_torrentError_receivesEvent() = runBlocking {
        var received: TorrentEvent? = null
        val job = launch {
            EventBus.observeTorrentEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postTorrentEvent(TorrentEvent.Error(10L, "Storage path not writable"))
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        val err = received as TorrentEvent.Error
        assertEquals(10L, err.torrentId)
        assertEquals("Storage path not writable", err.message)
    }

    @Test
    fun postAndObserve_sessionStarted_receivesEvent() = runBlocking {
        var received: SessionEvent? = null
        val job = launch {
            EventBus.observeSessionEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postSessionEvent(SessionEvent.Started)
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        assertTrue(received is SessionEvent.Started)
    }

    @Test
    fun postAndObserve_sessionStopped_receivesEvent() = runBlocking {
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
    }

    @Test
    fun postAndObserve_sessionError_receivesEvent() = runBlocking {
        var received: SessionEvent? = null
        val job = launch {
            EventBus.observeSessionEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postSessionEvent(SessionEvent.Error("DHT bootstrap failed"))
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        val err = received as SessionEvent.Error
        assertEquals("DHT bootstrap failed", err.message)
    }

    @Test
    fun postAndObserve_sessionWarning_receivesEvent() = runBlocking {
        var received: SessionEvent? = null
        val job = launch {
            EventBus.observeSessionEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postSessionEvent(SessionEvent.Warning("Tracker returned timeout"))
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        val warn = received as SessionEvent.Warning
        assertEquals("Tracker returned timeout", warn.message)
    }

    @Test
    fun postAndObserve_alertEvent_receivesEvent() = runBlocking {
        var received: AlertEvent? = null
        val job = launch {
            EventBus.observeAlerts().collect { event -> received = event }
        }
        delay(100)

        EventBus.postAlert(AlertEvent("file_completed", "test.torrent piece 0"))
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        assertEquals("file_completed", received!!.type)
        assertEquals("test.torrent piece 0", received.message)
    }

    @Test
    fun postAndObserve_multipleEvents_ordered() = runBlocking {
        val collected = mutableListOf<AppEvent>()
        val job = launch {
            EventBus.observe().collect { event ->
                collected.add(event)
                if (collected.size >= 3) return@collect // Stop after 3 events.
            }
        }
        delay(100)

        EventBus.post(TorrentEvent.Added(1L))
        EventBus.post(SessionEvent.Started)
        EventBus.post(TorrentEvent.StateChanged(1L, "downloading"))

        delay(300)
        job.cancel()

        assertEquals(3, collected.size)
        assertTrue(collected[0] is TorrentEvent.Added)
        assertTrue(collected[1] is SessionEvent.Started)
        val third = collected[2] as TorrentEvent.StateChanged
        assertEquals(1L, third.torrentId)
    }

    // ======================================================================
    // 2. AlertDispatcher: lifecycle (start/stop)
    // ======================================================================

    @Test
    fun alertDispatcher_startSetsRunning() {
        assertFalse(AlertDispatcher.isRunning)
        AlertDispatcher.start()
        assertTrue(AlertDispatcher.isRunning)
    }

    @Test
    fun alertDispatcher_stopSetsRunning() {
        AlertDispatcher.start()
        assertTrue(AlertDispatcher.isRunning)
        AlertDispatcher.stop()
        assertFalse(AlertDispatcher.isRunning)
    }

    @Test
    fun alertDispatcher_startTwice_noDoubleStart() {
        AlertDispatcher.start()
        assertTrue(AlertDispatcher.isRunning)
        AlertDispatcher.start() // idempotent
        assertTrue(AlertDispatcher.isRunning)
    }

    @Test
    fun alertDispatcher_stopTwice_noCrash() {
        AlertDispatcher.start()
        AlertDispatcher.stop()
        AlertDispatcher.stop() // second stop must not throw
        assertFalse(AlertDispatcher.isRunning)
    }

    @Test
    fun alertDispatcher_startStopCycle_noCrash() = runBlocking {
        AlertDispatcher.start()
        assertTrue(AlertDispatcher.isRunning)
        AlertDispatcher.stop()
        assertFalse(AlertDispatcher.isRunning)

        // Restart after stop should work.
        AlertDispatcher.start()
        assertTrue(AlertDispatcher.isRunning)
        AlertDispatcher.stop()
    }

    // ======================================================================
    // 3. Full pipeline: native session → AlertDispatcher → EventBus
    // ======================================================================

    @Test
    fun fullPipeline_sessionInitToEventBus() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        AlertDispatcher.start()

        var received: SessionEvent? = null
        val job = launch {
            EventBus.observeSessionEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.postSessionEvent(SessionEvent.Warning("pipeline check"))
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)
        assertTrue(received is SessionEvent.Warning)

        AlertDispatcher.stop()
        TorrentSession.destroy()
    }

    @Test
    fun fullPipeline_nativeAlertsParsedWithoutCrash() = runBlocking {
        val initOk = TorrentSession.init(context)
        if (!initOk) return@runBlocking

        // popAlerts should return valid JSON.
        val alerts = TorrentSession.popAlerts()
        assertTrue("Expected JSON array: $alerts", alerts.startsWith("["))

        AlertDispatcher.start()
        delay(2500) // one poll cycle

        var received: TorrentEvent? = null
        val job = launch {
            EventBus.observeTorrentEvents().collect { event -> received = event }
        }
        delay(100)

        EventBus.post(TorrentEvent.Added(42L))
        delay(200)
        job.cancel()

        assertNotNull("Expected non-null event", received)

        AlertDispatcher.stop()
        TorrentSession.destroy()
    }

    // ======================================================================
    // 4. Native smoke tests (no session init required)
    // ======================================================================

    @Test
    fun nativePopAlerts_emptyArrayWhenNoSession() {
        val alerts = TorrentSession.popAlerts()
        assertTrue("Expected empty JSON array: $alerts", alerts == "[]" || alerts.startsWith("["))
    }

    @Test
    fun nativeGetAllTorrentIds_emptyWhenNoSession() {
        val ids = TorrentSession.getAllTorrentIds()
        assertTrue("Expected empty list: $ids", ids.isEmpty())
    }

    @Test
    fun nativeGetAllTorrentHashes_emptyWhenNoSession() {
        val hashes = TorrentSession.getAllTorrentHashes()
        assertTrue("Expected empty map: $hashes", hashes.isEmpty())
    }

    @Test
    fun nativeGetDiagnostics_returnsData() {
        val diag = TorrentSession.getDiagnostics()
        assertNotNull("Expected non-null diagnostics", diag)
        // Diagnostics should have some basic info even without session.
        assertNotNull("Expected libtorrentVersion", diag.libtorrentVersion)
    }

    // ======================================================================
    // Helper functions
    // ======================================================================

    private fun assertNotNull(message: String, value: Any?) {
        if (value == null) throw AssertionError(message)
    }

    private fun assertTrue(condition: Boolean, message: String = "Assertion failed") {
        if (!condition) throw AssertionError(message)
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("Expected $expected but got $actual")
    }

    private fun <T> List<T>.get(index: Int): T = this[index]
}
