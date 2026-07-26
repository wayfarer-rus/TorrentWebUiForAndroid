package com.andreiefimov.torrentwebui

internal data class PasswordResetUiState(
    val confirmationRequired: Boolean = false,
    val message: String? = null
)

/**
 * Android-local recovery state machine. It deliberately accepts no password input and never
 * touches daemon, WebUI-server, or native-session lifecycle.
 */
internal class PasswordResetController(
    private val authManager: AuthManager
) {
    var state: PasswordResetUiState = PasswordResetUiState()
        private set

    fun requestConfirmation(): PasswordResetUiState {
        state = PasswordResetUiState(confirmationRequired = true)
        return state
    }

    fun cancel(): PasswordResetUiState {
        state = PasswordResetUiState()
        return state
    }

    fun confirm(): PasswordResetUiState {
        if (!state.confirmationRequired) return state

        val saved = authManager.setPassword(WebUiCredentials.DEFAULT_PASSWORD)
        state = PasswordResetUiState(
            message = if (saved) {
                "WebUI Password reset. Reauthenticate open browsers."
            } else {
                "WebUI Password reset could not be saved. Try again."
            }
        )
        return state
    }
}
