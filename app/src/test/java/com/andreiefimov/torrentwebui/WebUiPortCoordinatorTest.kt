package com.andreiefimov.torrentwebui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiPortCoordinatorTest {

    @Test
    fun `default configured port is 8080 and cold start publishes it as effective`() {
        val fixture = fixture()

        val status = fixture.coordinator.startConfigured()

        assertEquals(8080, status.configuredPort)
        assertEquals(8080, status.effectivePort)
        assertNull(status.operationError)
        assertEquals(listOf(8080), fixture.factory.createdPorts)
    }

    @Test
    fun `port range accepts boundaries and rejects values outside them`() {
        assertTrue(WebUiPort.isValid(1024))
        assertTrue(WebUiPort.isValid(65535))
        assertFalse(WebUiPort.isValid(1023))
        assertFalse(WebUiPort.isValid(65536))
    }

    @Test
    fun `invalid input leaves active server and configured port unchanged`() {
        val fixture = fixture()
        fixture.coordinator.startConfigured()
        val original = fixture.factory.engines.single()

        listOf("", "not-a-port", "1023", "65536", "-1").forEach { input ->
            val status = fixture.coordinator.apply(input)

            assertEquals(8080, status.configuredPort)
            assertEquals(8080, status.effectivePort)
            assertEquals(WebUiPort.INVALID_PORT_ERROR, status.operationError)
        }
        assertEquals(listOf(8080), fixture.factory.createdPorts)
        assertEquals(emptyList<Int>(), fixture.store.writeAttempts)
        assertEquals(0, original.stopCount)
    }

    @Test
    fun `successful switch binds then persists then promotes candidate`() {
        val events = mutableListOf<String>()
        val fixture = fixture(events = events)
        fixture.coordinator.startConfigured()
        events.clear()

        val status = fixture.coordinator.apply("8081")

        assertEquals(listOf("start:8081", "persist:8081", "stop:8080"), events)
        assertEquals(8081, status.configuredPort)
        assertEquals(8081, status.effectivePort)
        assertNull(status.operationError)
        assertEquals(8081, fixture.store.read())
        assertTrue(fixture.controller.isRunning)
    }

    @Test
    fun `bind failure preserves previous server and persisted port`() {
        val fixture = fixture(failingPort = 8081)
        fixture.coordinator.startConfigured()
        val original = fixture.factory.engines.first()

        val status = fixture.coordinator.apply("8081")

        assertEquals(8080, status.configuredPort)
        assertEquals(8080, status.effectivePort)
        assertEquals("Port 8081 is unavailable. WebUI remains on port 8080.", status.operationError)
        assertEquals(8080, fixture.store.read())
        assertEquals(emptyList<Int>(), fixture.store.writeAttempts)
        assertEquals(0, original.stopCount)
        assertEquals(1, fixture.factory.engines.last().stopCount)
    }

    @Test
    fun `persistence failure discards candidate and preserves previous server`() {
        val fixture = fixture(persistSucceeds = false)
        fixture.coordinator.startConfigured()
        val original = fixture.factory.engines.first()

        val status = fixture.coordinator.apply("8081")

        assertEquals(8080, status.configuredPort)
        assertEquals(8080, status.effectivePort)
        assertEquals("Could not save WebUI port. WebUI remains on port 8080.", status.operationError)
        assertEquals(8080, fixture.store.read())
        assertEquals(listOf(8081, 8080), fixture.store.writeAttempts)
        assertEquals(0, original.stopCount)
        assertEquals(1, fixture.factory.engines.last().stopCount)
    }

    @Test
    fun `persistence rollback retries candidate retirement without disturbing old server`() {
        val fixture = fixture(
            persistSucceeds = false,
            stopFailuresByPort = mapOf(8081 to 1)
        )
        fixture.coordinator.startConfigured()
        val original = fixture.factory.engines.first()

        val status = fixture.coordinator.apply("8081")

        assertEquals(8080, status.configuredPort)
        assertEquals(8080, status.effectivePort)
        assertEquals(0, original.stopCount)
        assertEquals(2, fixture.factory.engines.last().stopCount)
    }

    @Test
    fun `persistence exception discards candidate and preserves previous server`() {
        val fixture = fixture(persistThrows = true)
        fixture.coordinator.startConfigured()
        val original = fixture.factory.engines.first()

        val status = fixture.coordinator.apply("8081")

        assertEquals(8080, status.configuredPort)
        assertEquals(8080, status.effectivePort)
        assertEquals("Could not save WebUI port. WebUI remains on port 8080.", status.operationError)
        assertEquals(8080, fixture.store.read())
        assertEquals(0, original.stopCount)
        assertEquals(1, fixture.factory.engines.last().stopCount)
    }

    @Test
    fun `cold start bind failure reports unavailable without replacing configured port`() {
        val fixture = fixture(initialPort = 8090, failingPort = 8090)

        val status = fixture.coordinator.startConfigured()

        assertEquals(8090, status.configuredPort)
        assertNull(status.effectivePort)
        assertEquals("WebUI could not start on configured port 8090.", status.operationError)
        assertEquals(8090, fixture.store.read())
        assertEquals(emptyList<Int>(), fixture.store.writeAttempts)
    }

    @Test
    fun `persisted successful port is used after process-style reconstruction`() {
        val store = FakeWebUiPortStore(8080)
        val first = fixture(store = store)
        first.coordinator.startConfigured()
        first.coordinator.apply("9090")
        first.coordinator.stop()

        val restarted = fixture(store = store)
        val status = restarted.coordinator.startConfigured()

        assertEquals(9090, status.configuredPort)
        assertEquals(9090, status.effectivePort)
        assertEquals(listOf(9090), restarted.factory.createdPorts)
    }

    @Test
    fun `promotion keeps new server active when old retirement needs cleanup retry`() {
        val fixture = fixture(stopFailuresByPort = mapOf(8080 to 1))
        fixture.coordinator.startConfigured()

        val status = fixture.coordinator.apply("8081")

        assertEquals(8081, status.configuredPort)
        assertEquals(8081, status.effectivePort)
        assertEquals(WebUiPort.OLD_SERVER_CLEANUP_ERROR, status.operationError)
        assertTrue(fixture.controller.isRunning)

        val cleaned = fixture.coordinator.retryRetiredServers()
        assertNull(cleaned.operationError)
        assertEquals(2, fixture.factory.engines.first().stopCount)

        assertEquals(WebUiServerStopResult.Stopped, fixture.coordinator.stop())
        assertEquals(1, fixture.factory.engines.last().stopCount)
    }

    private fun fixture(
        initialPort: Int = 8080,
        failingPort: Int? = null,
        persistSucceeds: Boolean = true,
        persistThrows: Boolean = false,
        stopFailuresByPort: Map<Int, Int> = emptyMap(),
        events: MutableList<String> = mutableListOf(),
        store: FakeWebUiPortStore = FakeWebUiPortStore(
            initialPort,
            persistSucceeds,
            persistThrows,
            events
        )
    ): Fixture {
        val factory = RecordingEngineFactory(failingPort, stopFailuresByPort, events)
        val controller = WebUiServerController(factory)
        return Fixture(WebUiPortCoordinator(controller, store), controller, factory, store)
    }

    private data class Fixture(
        val coordinator: WebUiPortCoordinator,
        val controller: WebUiServerController,
        val factory: RecordingEngineFactory,
        val store: FakeWebUiPortStore
    )

    private class FakeWebUiPortStore(
        private var value: Int,
        private val persistSucceeds: Boolean = true,
        private val persistThrows: Boolean = false,
        private val events: MutableList<String> = mutableListOf()
    ) : WebUiPortStore {
        val writeAttempts = mutableListOf<Int>()

        override fun read(): Int = value

        override fun write(port: Int): Boolean {
            writeAttempts += port
            events += "persist:$port"
            if (persistThrows) throw IllegalStateException("storage unavailable")
            // SharedPreferences updates its in-memory map before commit() reports disk failure.
            value = port
            return persistSucceeds
        }
    }

    private class RecordingEngineFactory(
        private val failingPort: Int?,
        private val stopFailuresByPort: Map<Int, Int>,
        private val events: MutableList<String>
    ) : WebUiServerEngineFactory {
        val createdPorts = mutableListOf<Int>()
        val engines = mutableListOf<RecordingEngine>()

        override fun create(port: Int): WebUiServerEngine {
            createdPorts += port
            return RecordingEngine(
                port = port,
                failsOnStart = port == failingPort,
                stopFailuresRemaining = stopFailuresByPort[port] ?: 0,
                events = events
            ).also(engines::add)
        }
    }

    private class RecordingEngine(
        private val port: Int,
        private val failsOnStart: Boolean,
        private var stopFailuresRemaining: Int,
        private val events: MutableList<String>
    ) : WebUiServerEngine {
        var stopCount = 0

        override fun start() {
            events += "start:$port"
            if (failsOnStart) throw IllegalStateException("port unavailable")
        }

        override fun stop() {
            stopCount++
            events += "stop:$port"
            if (stopFailuresRemaining > 0) {
                stopFailuresRemaining--
                throw IllegalStateException("shutdown failed")
            }
        }
    }
}
