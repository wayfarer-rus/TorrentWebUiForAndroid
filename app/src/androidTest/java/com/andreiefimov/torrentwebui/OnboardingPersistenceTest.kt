package com.andreiefimov.torrentwebui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingPersistenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearState()
    }

    @After
    fun tearDown() = clearState()

    @Test
    fun incompleteMarkerSurvivesReconstructionAndWinsOverMigrationEvidence() = runBlocking {
        val first = coordinator()
        assertFalse(first.status().completed)

        val reconstructed = coordinator(
            hasQueue = true,
            hasDestination = true,
            hasNonDefaultPassword = true
        ).status()

        assertFalse(reconstructed.completed)
        assertTrue(reconstructed.hasApprovedDestination)
        assertEquals(PasswordDecision.Pending, reconstructed.passwordDecision)
    }

    @Test
    fun deferredEligibleOnboardingCompletesDurablyAcrossReconstructionAndRecoveryLoss() = runBlocking {
        val first = coordinator()
        assertFalse(first.status().completed)
        val deferred = coordinator(hasDestination = true).deferPassword() as PasswordDeferralResult.Updated
        assertTrue(deferred.status.completed)

        val reconstructed = coordinator(
            hasDestination = false,
            readiness = OnboardingReadiness.ServiceUnavailable
        ).status()

        assertTrue(reconstructed.completed)
        assertEquals(PasswordDecision.Deferred, reconstructed.passwordDecision)
    }

    @Test
    fun changedPasswordAndDecisionSurviveReauthenticationInterruption() = runBlocking {
        val firstAuth = DefaultAuthManager(context)
        val first = coordinator()
        assertFalse(first.status().completed)
        val changed = coordinator(hasDestination = true).changePassword(
            "household-passphrase",
            firstAuth::setPassword
        ) as OnboardingPasswordChangeResult.Updated
        assertTrue(changed.status.completed)

        val reconstructedAuth = DefaultAuthManager(context)
        val reconstructed = coordinator(
            hasDestination = false,
            readiness = OnboardingReadiness.ServiceUnavailable
        ).status()

        assertEquals("household-passphrase", reconstructedAuth.getPassword())
        assertTrue(reconstructed.completed)
        assertEquals(PasswordDecision.Changed, reconstructed.passwordDecision)
    }

    @Test
    fun establishedQueueMigratesOnlyWhenMarkerIsAbsent() = runBlocking {
        val migrated = coordinator(hasQueue = true).status()

        assertTrue(migrated.completed)
        assertEquals(PasswordDecision.Deferred, migrated.passwordDecision)
        assertEquals(
            OnboardingRecord(true, PasswordDecision.Deferred),
            SharedPreferencesOnboardingStateStore(context).read()
        )
    }

    private fun coordinator(
        hasQueue: Boolean = false,
        hasDestination: Boolean = false,
        hasNonDefaultPassword: Boolean = false,
        readiness: OnboardingReadiness = OnboardingReadiness.Ready
    ) = OnboardingCoordinator(
        store = SharedPreferencesOnboardingStateStore(context),
        hasDurableQueue = { hasQueue },
        hasApprovedDestination = { hasDestination },
        hasNonDefaultPassword = { hasNonDefaultPassword },
        readiness = { readiness }
    )

    private fun clearState() {
        context.getSharedPreferences(
            SharedPreferencesOnboardingStateStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        context.getSharedPreferences(
            DefaultAuthManager.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }
}
