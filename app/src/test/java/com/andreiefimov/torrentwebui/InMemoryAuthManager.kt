package com.andreiefimov.torrentwebui

/**
 * In-memory [AuthManager] for unit tests. Default password is `start123`.
 */
class InMemoryAuthManager(
    initialPassword: String = WebUiCredentials.DEFAULT_PASSWORD,
    private val persistenceSucceeds: Boolean = true
) : AuthManager {
    @Volatile private var password: String = initialPassword

    override fun getPassword(): String = password

    override fun setPassword(newPassword: String): Boolean {
        if (!persistenceSucceeds) return false
        password = newPassword
        return true
    }
}
