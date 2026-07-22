package com.andreiefimov.torrentwebui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedJobSlotTest {
    @Test
    fun `stopped collector can be replaced after a failed daemon stop`() = runTest {
        val slot = OwnedJobSlot()
        var active = 0
        var starts = 0

        suspend fun collectForever() {
            starts++
            active++
            try {
                awaitCancellation()
            } finally {
                active--
            }
        }

        slot.replace(backgroundScope) { collectForever() }
        runCurrent()
        slot.stop()
        runCurrent()
        assertEquals(0, active)

        slot.replace(backgroundScope) { collectForever() }
        runCurrent()
        assertEquals(2, starts)
        assertEquals(1, active)
        slot.stop()
    }

    @Test
    fun `replace cancels prior collector before starting one replacement`() = runTest {
        val slot = OwnedJobSlot()
        var active = 0
        var starts = 0
        var stops = 0

        suspend fun collectForever() {
            starts++
            active++
            try {
                awaitCancellation()
            } finally {
                active--
                stops++
            }
        }

        slot.replace(backgroundScope) { collectForever() }
        runCurrent()
        assertEquals(1, active)

        slot.replace(backgroundScope) { collectForever() }
        runCurrent()
        assertEquals(2, starts)
        assertEquals(1, stops)
        assertEquals(1, active)

        slot.stop()
        runCurrent()
        assertEquals(2, stops)
        assertEquals(0, active)
    }
}
