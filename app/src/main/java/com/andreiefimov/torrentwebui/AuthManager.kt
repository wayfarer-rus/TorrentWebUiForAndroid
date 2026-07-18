package com.andreiefimov.torrentwebui

/**
 * Manages the WebUI password. Read by the Ktor auth middleware, written by
 * the password change endpoint and Android settings.
 */
interface AuthManager {
    /** Returns the current password. */
    fun getPassword(): String

    /** Persists a new password. Takes effect immediately on the next auth check. */
    fun setPassword(newPassword: String)
}
