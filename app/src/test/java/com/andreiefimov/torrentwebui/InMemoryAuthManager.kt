package com.andreiefimov.torrentwebui

/**
 * In-memory [AuthManager] for unit tests. Default password is `start123`.
 */
class InMemoryAuthManager(
    initialPassword: String = "start123"
) : AuthManager {
    @Volatile private var password: String = initialPassword

    override fun getPassword(): String = password

    override fun setPassword(newPassword: String) {
        password = newPassword
    }
}
