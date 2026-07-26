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
        hasNonDefaultPassword: Boolean = false
    ) = OnboardingCoordinator(
        store = SharedPreferencesOnboardingStateStore(context),
        hasDurableQueue = { hasQueue },
        hasApprovedDestination = { hasDestination },
        hasNonDefaultPassword = { hasNonDefaultPassword },
        readiness = { OnboardingReadiness.Ready }
    )

    private fun clearState() {
        context.getSharedPreferences(
            SharedPreferencesOnboardingStateStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }
}
