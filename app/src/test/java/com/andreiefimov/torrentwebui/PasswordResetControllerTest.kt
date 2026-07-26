package com.andreiefimov.torrentwebui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordResetControllerTest {

    @Test
    fun `cancelling confirmation leaves authentication unchanged`() {
        val auth = InMemoryAuthManager("forgotten-password")
        val controller = PasswordResetController(auth)

        assertTrue(controller.requestConfirmation().confirmationRequired)
        val cancelled = controller.cancel()

        assertFalse(cancelled.confirmationRequired)
        assertNull(cancelled.message)
        assertEquals("forgotten-password", auth.getPassword())
    }

    @Test
    fun `confirmed reset restores default and invalidates old browser credentials`() {
        val auth = InMemoryAuthManager("forgotten-password")
        val controller = PasswordResetController(auth)
        controller.requestConfirmation()

        val confirmed = controller.confirm()

        assertFalse(confirmed.confirmationRequired)
        assertEquals("WebUI Password reset. Reauthenticate open browsers.", confirmed.message)
        assertFalse(constantTimeEquals("forgotten-password", auth.getPassword()))
        assertTrue(constantTimeEquals(WebUiCredentials.DEFAULT_PASSWORD, auth.getPassword()))
    }

    @Test
    fun `failed reset persistence reports failure and keeps authentication`() {
        val auth = InMemoryAuthManager("forgotten-password", persistenceSucceeds = false)
        val controller = PasswordResetController(auth)
        controller.requestConfirmation()

        val failed = controller.confirm()

        assertEquals("WebUI Password reset could not be saved. Try again.", failed.message)
        assertEquals("forgotten-password", auth.getPassword())
    }

    @Test
    fun `confirm without a pending confirmation does not reset authentication`() {
        val auth = InMemoryAuthManager("forgotten-password")
        val controller = PasswordResetController(auth)

        val unchanged = controller.confirm()

        assertFalse(unchanged.confirmationRequired)
        assertNull(unchanged.message)
        assertEquals("forgotten-password", auth.getPassword())
    }
}
