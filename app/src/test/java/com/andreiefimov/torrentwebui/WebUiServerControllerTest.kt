package com.andreiefimov.torrentwebui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiServerControllerTest {

    @Test
    fun `start owns one active server and shutdown is idempotent`() {
        val factory = RecordingWebUiServerEngineFactory()
        val controller = WebUiServerController(factory)

        controller.start(8080)
        controller.start(8080)

        assertTrue(controller.isRunning)
        assertEquals(listOf(8080), factory.createdPorts)

        val active = factory.engines.single()
        assertEquals(1, active.startCount)

        assertEquals(WebUiServerStopResult.Stopped, controller.stop())
        assertEquals(WebUiServerStopResult.Stopped, controller.stop())

        assertFalse(controller.isRunning)
        assertEquals(1, active.stopCount)
    }

    @Test
    fun `candidate binds beside active server and promotion retires only the old server`() {
        val factory = RecordingWebUiServerEngineFactory()
        val controller = WebUiServerController(factory)
        controller.start(8080)
        val original = factory.engines.single()

        val candidate = controller.bindCandidate(8081)
        val replacement = factory.engines.last()

        assertEquals(listOf(8080, 8081), factory.createdPorts)
        assertEquals(0, original.stopCount)
        assertEquals(1, replacement.startCount)

        controller.promote(candidate)

        assertTrue(controller.isRunning)
        assertEquals(1, original.stopCount)
        assertEquals(0, replacement.stopCount)

        controller.stop()
        assertEquals(1, replacement.stopCount)
    }

    @Test
    fun `discard rolls back candidate without disturbing active server`() {
        val factory = RecordingWebUiServerEngineFactory()
        val controller = WebUiServerController(factory)
        controller.start(8080)
        val original = factory.engines.single()

        val candidate = controller.bindCandidate(8081)
        val replacement = factory.engines.last()
        controller.discard(candidate)

        assertTrue(controller.isRunning)
        assertEquals(0, original.stopCount)
        assertEquals(1, replacement.stopCount)

        controller.stop()
        assertEquals(1, original.stopCount)
    }

    @Test
    fun `candidate bind failure leaves active server unchanged`() {
        val factory = RecordingWebUiServerEngineFactory(failingPort = 8081)
        val controller = WebUiServerController(factory)
        controller.start(8080)
        val original = factory.engines.single()

        try {
            controller.bindCandidate(8081)
            org.junit.Assert.fail("Expected candidate bind to fail")
        } catch (_: IllegalStateException) {
            // Expected from the fake engine.
        }

        assertTrue(controller.isRunning)
        assertEquals(0, original.stopCount)
        assertEquals(1, factory.engines.last().stopCount)

        controller.stop()
        assertEquals(1, original.stopCount)
    }

    @Test
    fun `failed candidate cleanup remains owned for controller shutdown`() {
        val factory = RecordingWebUiServerEngineFactory(
            failingPort = 8081,
            stopFailuresByPort = mapOf(8081 to 1)
        )
        val controller = WebUiServerController(factory)
        controller.start(8080)
        val original = factory.engines.single()

        try {
            controller.bindCandidate(8081)
            org.junit.Assert.fail("Expected candidate bind to fail")
        } catch (failure: IllegalStateException) {
            assertEquals(1, failure.suppressed.size)
        }
        val failedCandidate = factory.engines.last()
        assertEquals(1, failedCandidate.stopCount)
        assertEquals(0, original.stopCount)

        controller.stop()

        assertFalse(controller.isRunning)
        assertEquals(2, failedCandidate.stopCount)
        assertEquals(1, original.stopCount)
    }

    @Test
    fun `promotion commits candidate and retains failed old server cleanup`() {
        val factory = RecordingWebUiServerEngineFactory(stopFailuresByPort = mapOf(8080 to 1))
        val controller = WebUiServerController(factory)
        controller.start(8080)
        val original = factory.engines.single()
        val candidate = controller.bindCandidate(8081)
        val replacement = factory.engines.last()

        assertEquals(WebUiServerPromotionResult.PromotedCleanupRequired, controller.promote(candidate))

        assertTrue(controller.isRunning)
        assertEquals(1, original.stopCount)
        assertEquals(0, replacement.stopCount)

        controller.stop()
        assertEquals(2, original.stopCount)
        assertEquals(1, replacement.stopCount)
    }

    @Test
    fun `discard retains candidate ownership until shutdown succeeds`() {
        val factory = RecordingWebUiServerEngineFactory(stopFailuresByPort = mapOf(8081 to 1))
        val controller = WebUiServerController(factory)
        controller.start(8080)
        val candidate = controller.bindCandidate(8081)
        val replacement = factory.engines.last()

        try {
            controller.discard(candidate)
            org.junit.Assert.fail("Expected candidate shutdown to fail")
        } catch (_: IllegalStateException) {
            // The candidate remains owned for a retry.
        }

        assertEquals(1, replacement.stopCount)
        controller.discard(candidate)
        assertEquals(2, replacement.stopCount)

        controller.stop()
    }

    @Test
    fun `failed shutdown retains engine ownership for retry`() {
        val factory = RecordingWebUiServerEngineFactory(stopFailuresByPort = mapOf(8080 to 1))
        val controller = WebUiServerController(factory)
        controller.start(8080)
        val active = factory.engines.single()

        assertEquals(WebUiServerStopResult.RetryRequired, controller.stop())

        assertTrue(controller.isRunning)
        assertEquals(1, active.stopCount)

        assertEquals(WebUiServerStopResult.Stopped, controller.stop())
        assertFalse(controller.isRunning)
        assertEquals(2, active.stopCount)
    }

    @Test
    fun `shutdown discards a pending candidate and remains idempotent`() {
        val factory = RecordingWebUiServerEngineFactory()
        val controller = WebUiServerController(factory)
        controller.start(8080)
        controller.bindCandidate(8081)
        val original = factory.engines.first()
        val candidate = factory.engines.last()

        assertEquals(WebUiServerStopResult.Stopped, controller.stop())
        assertEquals(WebUiServerStopResult.Stopped, controller.stop())

        assertFalse(controller.isRunning)
        assertEquals(1, original.stopCount)
        assertEquals(1, candidate.stopCount)
    }

    private class RecordingWebUiServerEngineFactory(
        private val failingPort: Int? = null,
        private val stopFailuresByPort: Map<Int, Int> = emptyMap()
    ) : WebUiServerEngineFactory {
        val createdPorts = mutableListOf<Int>()
        val engines = mutableListOf<RecordingWebUiServerEngine>()

        override fun create(port: Int): WebUiServerEngine {
            createdPorts += port
            return RecordingWebUiServerEngine(
                failsOnStart = port == failingPort,
                stopFailuresRemaining = stopFailuresByPort[port] ?: 0
            ).also(engines::add)
        }
    }

    private class RecordingWebUiServerEngine(
        private val failsOnStart: Boolean = false,
        private var stopFailuresRemaining: Int = 0
    ) : WebUiServerEngine {
        var startCount = 0
        var stopCount = 0

        override fun start() {
            startCount++
            if (failsOnStart) throw IllegalStateException("port unavailable")
        }

        override fun stop() {
            stopCount++
            if (stopFailuresRemaining > 0) {
                stopFailuresRemaining--
                throw IllegalStateException("shutdown failed")
            }
        }
    }
}
