package com.andreiefimov.torrentwebui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PasswordResetUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cancellationRequiresConfirmationAndLeavesAuthenticationUnchanged() {
        val auth = RecordingAuthManager("forgotten-password")
        composeRule.setContent {
            MaterialTheme { PasswordRecoveryCard(auth) }
        }

        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeRule.onNodeWithText("Reset WebUI Password").performClick()
        composeRule.onNodeWithText("Reset WebUI Password?").assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)

        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Reset WebUI Password?").assertDoesNotExist()
        assertEquals("forgotten-password", auth.getPassword())
    }

    @Test
    fun confirmationResetsPasswordWithoutRenderingPasswordOrTorrentControls() {
        val auth = RecordingAuthManager("forgotten-password")
        composeRule.setContent {
            MaterialTheme { PasswordRecoveryCard(auth) }
        }

        composeRule.onNodeWithText("Reset WebUI Password").performClick()
        composeRule.onNodeWithText("Reset Password").performClick()

        assertEquals(WebUiCredentials.DEFAULT_PASSWORD, auth.getPassword())
        composeRule.onNodeWithText("WebUI Password reset. Reauthenticate open browsers.")
            .assertIsDisplayed()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        composeRule.onNodeWithText("Add Torrent").assertDoesNotExist()
        composeRule.onNodeWithText("Pause").assertDoesNotExist()
        composeRule.onNodeWithText("Remove").assertDoesNotExist()
    }

    private class RecordingAuthManager(initialPassword: String) : AuthManager {
        private var password = initialPassword

        override fun getPassword(): String = password

        override fun setPassword(newPassword: String): Boolean {
            password = newPassword
            return true
        }
    }
}
