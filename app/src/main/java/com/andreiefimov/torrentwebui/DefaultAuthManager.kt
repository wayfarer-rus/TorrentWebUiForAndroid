package com.andreiefimov.torrentwebui

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed [AuthManager]. Default password is `start123`.
 */
class DefaultAuthManager(context: Context) : AuthManager {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("webui_auth", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PASSWORD = "webui_password"
        private const val DEFAULT_PASSWORD = "start123"
    }

    override fun getPassword(): String = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD)!!

    override fun setPassword(newPassword: String) {
        prefs.edit().putString(KEY_PASSWORD, newPassword).apply()
    }
}
