package com.andreiefimov.torrentwebui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingCoordinatorTest {

    @Test
    fun `unused installation creates durable incomplete marker`() = runTest {
        val store = RecordingOnboardingStateStore()
        val coordinator = coordinator(store = store)

        val status = coordinator.status()

        assertFalse(status.completed)
        assertEquals(PasswordDecision.Pending, status.passwordDecision)
        assertFalse(status.hasApprovedDestination)
        assertEquals(OnboardingReadiness.Ready, status.readiness)
        assertEquals(OnboardingRecord(false, PasswordDecision.Pending), store.record)
        coordinator.status()
        assertEquals(1, store.writeCount)
    }

    @Test
    fun `durable queue migrates first M6 startup to completed with password deferred`() = runTest {
        val store = RecordingOnboardingStateStore()
        val coordinator = coordinator(store = store, hasQueue = true)

        val status = coordinator.status()

        assertTrue(status.completed)
        assertEquals(PasswordDecision.Deferred, status.passwordDecision)
        assertEquals(OnboardingRecord(true, PasswordDecision.Deferred), store.record)
    }

    @Test
    fun `approved destination or non-default password proves established installation`() = runTest {
        val destination = coordinator(hasDestination = true).status()
        val password = coordinator(hasNonDefaultPassword = true).status()

        assertTrue(destination.completed)
        assertTrue(destination.hasApprovedDestination)
        assertEquals(PasswordDecision.Deferred, destination.passwordDecision)
        assertTrue(password.completed)
        assertEquals(PasswordDecision.Deferred, password.passwordDecision)
    }

    @Test
    fun `existing incomplete marker wins over later migration evidence`() = runTest {
        val store = RecordingOnboardingStateStore(
            OnboardingRecord(completed = false, passwordDecision = PasswordDecision.Pending)
        )
        val coordinator = coordinator(
            store = store,
            hasQueue = true,
            hasDestination = true,
            hasNonDefaultPassword = true
        )

        val status = coordinator.status()

        assertFalse(status.completed)
        assertTrue(status.hasApprovedDestination)
        assertEquals(PasswordDecision.Pending, status.passwordDecision)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun `completed marker remains complete through later readiness and destination loss`() = runTest {
        val store = RecordingOnboardingStateStore(
            OnboardingRecord(completed = true, passwordDecision = PasswordDecision.Changed)
        )
        val coordinator = coordinator(
            store = store,
            readiness = OnboardingReadiness.ServiceUnavailable
        )

        val status = coordinator.status()

        assertTrue(status.completed)
        assertFalse(status.hasApprovedDestination)
        assertEquals(PasswordDecision.Changed, status.passwordDecision)
        assertEquals(OnboardingReadiness.ServiceUnavailable, status.readiness)
    }

    @Test
    fun `failed initial marker persistence does not report initialized status`() = runTest {
        val store = RecordingOnboardingStateStore(writeSucceeds = false)
        val coordinator = coordinator(store = store)

        try {
            coordinator.status()
            throw AssertionError("Expected persistence failure")
        } catch (_: IllegalStateException) {
            // No status is returned until the incomplete marker is durable.
        }

        assertEquals(null, store.record)
    }

    @Test
    fun `readiness mapping exposes only consumer states`() {
        assertEquals(
            OnboardingReadiness.ActionNeededOnAndroid,
            mapOnboardingReadiness(StoragePermissionState.RevokedRuntime, sessionStarted = false)
        )
        assertEquals(
            OnboardingReadiness.ServiceUnavailable,
            mapOnboardingReadiness(StoragePermissionState.Ready, sessionStarted = false)
        )
        assertEquals(
            OnboardingReadiness.Ready,
            mapOnboardingReadiness(StoragePermissionState.Ready, sessionStarted = true)
        )
    }

    private fun coordinator(
        store: RecordingOnboardingStateStore = RecordingOnboardingStateStore(),
        hasQueue: Boolean = false,
        hasDestination: Boolean = false,
        hasNonDefaultPassword: Boolean = false,
        readiness: OnboardingReadiness = OnboardingReadiness.Ready
    ) = OnboardingCoordinator(
        store = store,
        hasDurableQueue = { hasQueue },
        hasApprovedDestination = { hasDestination },
        hasNonDefaultPassword = { hasNonDefaultPassword },
        readiness = { readiness }
    )

    private class RecordingOnboardingStateStore(
        initial: OnboardingRecord? = null,
        private val writeSucceeds: Boolean = true
    ) : OnboardingStateStore {
        var record = initial
        var writeCount = 0

        override fun read(): OnboardingRecord? = record

        override fun write(record: OnboardingRecord): Boolean {
            writeCount += 1
            if (writeSucceeds) this.record = record
            return writeSucceeds
        }
    }
}
