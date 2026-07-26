package com.andreiefimov.torrentwebui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PasswordResetPersistenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences().edit().clear().commit()
    }

    @After
    fun tearDown() {
        preferences().edit().clear().commit()
    }

    @Test
    fun confirmedResetIsImmediatelyVisibleToExistingAndReconstructedAuthenticationManagers() {
        val serverAuth = DefaultAuthManager(context)
        serverAuth.setPassword("forgotten-password")
        val reset = PasswordResetController(DefaultAuthManager(context))
        reset.requestConfirmation()

        reset.confirm()

        assertFalse(constantTimeEquals("forgotten-password", serverAuth.getPassword()))
        assertTrue(constantTimeEquals(WebUiCredentials.DEFAULT_PASSWORD, serverAuth.getPassword()))
        assertEquals(
            WebUiCredentials.DEFAULT_PASSWORD,
            DefaultAuthManager(context).getPassword()
        )
    }

    private fun preferences() = context.getSharedPreferences(
        DefaultAuthManager.PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
}
