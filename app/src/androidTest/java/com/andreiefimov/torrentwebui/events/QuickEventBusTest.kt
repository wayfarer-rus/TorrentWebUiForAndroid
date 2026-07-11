package com.andreiefimov.torrentwebui.events

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Quick test to verify EventBus basic functionality without native code.
 */
@RunWith(AndroidJUnit4::class)
class QuickEventBusTest {

    @Test
    fun postAndObserve_simple() = runBlocking {
        var received: SessionEvent? = null
        val job = launch {
            EventBus.observeSessionEvents().collect { event -> received = event }
        }
        delay(100) // Let collector start.

        EventBus.postSessionEvent(SessionEvent.Warning("test"))
        delay(200) // Let collector receive.
        job.cancel()

        assertNotNull("Expected non-null event", received)
        assertTrue(received is SessionEvent.Warning, "Expected SessionEvent.Warning")

        AlertDispatcher.stop()
    }

    @Test
    fun postAndObserve_multipleEvents() = runBlocking {
        val collected = mutableListOf<SessionEvent>()
        val job = launch {
            EventBus.observeSessionEvents().collect { event ->
                collected.add(event)
                if (collected.size >= 3) return@collect // Stop after 3 events.
            }
        }
        delay(100) // Let collector start.

        EventBus.postSessionEvent(SessionEvent.Started)
        EventBus.postSessionEvent(SessionEvent.Stopped)
        EventBus.postSessionEvent(SessionEvent.Warning("test"))

        delay(300) // Let collector receive all events.
        job.cancel()

        assertEquals(3, collected.size)
        assertTrue(collected[0] is SessionEvent.Started, "Expected Started")
        assertTrue(collected[1] is SessionEvent.Stopped, "Expected Stopped")
        assertTrue(collected[2] is SessionEvent.Warning, "Expected Warning")

        AlertDispatcher.stop()
    }

    @Test
    fun postAndObserve_torrentEvent() = runBlocking {
        var received: TorrentEvent? = null
        val job = launch {
            EventBus.observeTorrentEvents().collect { event -> received = event }
        }
        delay(100) // Let collector start.

        EventBus.postTorrentEvent(TorrentEvent.Added(42L))
        delay(200) // Let collector receive.
        job.cancel()

        assertNotNull("Expected non-null event", received)
        val event = received as TorrentEvent.Added
        assertEquals(42L, event.torrentId)

        AlertDispatcher.stop()
    }

    @Test
    fun postAndObserve_alertEvent() = runBlocking {
        var received: AlertEvent? = null
        val job = launch {
            EventBus.observeAlerts().collect { event -> received = event }
        }
        delay(100) // Let collector start.

        EventBus.postAlert(AlertEvent("test_type", "test_message"))
        delay(200) // Let collector receive.
        job.cancel()

        assertNotNull("Expected non-null event", received)
        assertEquals("test_type", received!!.type)
        assertEquals("test_message", received.message)

        AlertDispatcher.stop()
    }

    @Test
    fun observe_allEventTypes() = runBlocking {
        val collected = mutableListOf<AppEvent>()
        val job = launch {
            EventBus.observe().collect { event ->
                collected.add(event)
                if (collected.size >= 3) return@collect // Stop after 3 events.
            }
        }
        delay(100) // Let collector start.

        EventBus.post(TorrentEvent.Added(1L))
        EventBus.post(SessionEvent.Started)
        EventBus.postAlert(AlertEvent("type", "msg"))

        delay(300) // Let collector receive all events.
        job.cancel()

        assertEquals(3, collected.size)
        assertTrue(collected[0] is TorrentEvent.Added, "Expected TorrentEvent.Added")
        assertTrue(collected[1] is SessionEvent.Started, "Expected SessionEvent.Started")
        assertTrue(collected[2] is AlertEvent, "Expected AlertEvent")

        AlertDispatcher.stop()
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
