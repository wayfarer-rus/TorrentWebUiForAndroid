package com.andreiefimov.torrentwebui

/** Canonical WebUI credential defaults shared by authentication and Android recovery. */
internal object WebUiCredentials {
    const val DEFAULT_PASSWORD = "start123"
    const val MINIMUM_PASSWORD_LENGTH = 4

    fun normalizedPassword(value: String): String = value.trim()

    fun validationError(value: String): String? =
        if (normalizedPassword(value).length < MINIMUM_PASSWORD_LENGTH) {
            "New password must be at least 4 characters"
        } else {
            null
        }
}

/**
 * Manages the WebUI password. Read by the Ktor auth middleware, written by
 * the password change endpoint and Android settings.
 */
interface AuthManager {
    /** Returns the current password. */
    fun getPassword(): String

    /** Persists a new password synchronously. Takes effect on the next auth check. */
    fun setPassword(newPassword: String): Boolean
}
