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
    fun `password deferral is persisted before eligible completion`() = runTest {
        val store = RecordingOnboardingStateStore(
            OnboardingRecord(false, PasswordDecision.Pending)
        )
        val coordinator = coordinator(store = store, hasDestination = true)

        val result = coordinator.deferPassword()

        assertTrue(result is PasswordDeferralResult.Updated)
        assertEquals(
            listOf(
                OnboardingRecord(false, PasswordDecision.Deferred),
                OnboardingRecord(true, PasswordDecision.Deferred)
            ),
            store.writes
        )
        assertTrue((result as PasswordDeferralResult.Updated).status.completed)
    }

    @Test
    fun `deferred decision completes later when readiness becomes ready`() = runTest {
        var readiness = OnboardingReadiness.ServiceUnavailable
        val store = RecordingOnboardingStateStore(
            OnboardingRecord(false, PasswordDecision.Pending)
        )
        val coordinator = OnboardingCoordinator(
            store = store,
            hasDurableQueue = { false },
            hasApprovedDestination = { true },
            hasNonDefaultPassword = { false },
            readiness = { readiness }
        )

        val deferred = coordinator.deferPassword() as PasswordDeferralResult.Updated
        assertFalse(deferred.status.completed)
        assertEquals(PasswordDecision.Deferred, deferred.status.passwordDecision)

        readiness = OnboardingReadiness.Ready
        assertTrue(coordinator.status().completed)
    }

    @Test
    fun `deferral persistence failure leaves password decision pending`() = runTest {
        val store = RecordingOnboardingStateStore(
            initial = OnboardingRecord(false, PasswordDecision.Pending),
            writeSucceeds = false
        )
        val result = coordinator(store = store, hasDestination = true).deferPassword()

        assertEquals(PasswordDeferralResult.PersistenceFailed, result)
        assertEquals(OnboardingRecord(false, PasswordDecision.Pending), store.record)
    }

    @Test
    fun `password change persists credential before changed decision and completion`() = runTest {
        val events = mutableListOf<String>()
        val store = RecordingOnboardingStateStore(
            initial = OnboardingRecord(false, PasswordDecision.Pending),
            onWrite = { events += "state:${it.passwordDecision}:${it.completed}" }
        )
        val coordinator = coordinator(store = store, hasDestination = true)

        val result = coordinator.changePassword("household-passphrase") {
            events += "password"
            true
        }

        assertTrue(result is OnboardingPasswordChangeResult.Updated)
        assertEquals(
            listOf("password", "state:Changed:false", "state:Changed:true"),
            events
        )
        assertTrue((result as OnboardingPasswordChangeResult.Updated).status.completed)
    }

    @Test
    fun `password persistence failure leaves onboarding pending`() = runTest {
        val store = RecordingOnboardingStateStore(
            OnboardingRecord(false, PasswordDecision.Pending)
        )

        val result = coordinator(store = store, hasDestination = true)
            .changePassword("household-passphrase") { false }

        assertEquals(OnboardingPasswordChangeResult.PasswordPersistenceFailed, result)
        assertEquals(OnboardingRecord(false, PasswordDecision.Pending), store.record)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun `onboarding state failure occurs only after password persistence`() = runTest {
        var passwordPersisted = false
        val store = RecordingOnboardingStateStore(
            initial = OnboardingRecord(false, PasswordDecision.Pending),
            writeSucceeds = false
        )

        val result = coordinator(store = store, hasDestination = true).changePassword("another-pass") {
            passwordPersisted = true
            true
        }

        assertTrue(passwordPersisted)
        assertEquals(OnboardingPasswordChangeResult.StatePersistenceFailed, result)
        assertEquals(OnboardingRecord(false, PasswordDecision.Pending), store.record)
    }

    @Test
    fun `completed onboarding rejects onboarding password change`() = runTest {
        var persistenceCalls = 0
        val store = RecordingOnboardingStateStore(
            OnboardingRecord(true, PasswordDecision.Deferred)
        )

        val result = coordinator(store = store, hasDestination = true).changePassword("another-pass") {
            persistenceCalls += 1
            true
        }

        assertEquals(OnboardingPasswordChangeResult.AlreadyCompleted, result)
        assertEquals(0, persistenceCalls)
    }

    @Test
    fun `completed onboarding rejects password deferral`() = runTest {
        val store = RecordingOnboardingStateStore(
            OnboardingRecord(true, PasswordDecision.Deferred)
        )

        val result = coordinator(store = store, hasDestination = true).deferPassword()

        assertEquals(PasswordDeferralResult.AlreadyCompleted, result)
        assertEquals(0, store.writeCount)
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
        private val writeSucceeds: Boolean = true,
        private val onWrite: (OnboardingRecord) -> Unit = {}
    ) : OnboardingStateStore {
        var record = initial
        var writeCount = 0
        val writes = mutableListOf<OnboardingRecord>()

        override fun read(): OnboardingRecord? = record

        override fun write(record: OnboardingRecord): Boolean {
            writeCount += 1
            onWrite(record)
            if (writeSucceeds) {
                writes += record
                this.record = record
            }
            return writeSucceeds
        }
    }
}
