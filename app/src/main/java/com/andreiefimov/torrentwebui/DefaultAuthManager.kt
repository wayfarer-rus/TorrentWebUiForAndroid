package com.andreiefimov.torrentwebui

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed [AuthManager]. Default password is `start123`.
 */
class DefaultAuthManager(context: Context) : AuthManager {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    companion object {
        internal const val PREFERENCES_NAME = "webui_auth"
        internal const val KEY_PASSWORD = "webui_password"
    }

    override fun getPassword(): String =
        prefs.getString(KEY_PASSWORD, WebUiCredentials.DEFAULT_PASSWORD)!!

    override fun setPassword(newPassword: String): Boolean =
        prefs.edit().putString(KEY_PASSWORD, newPassword).commit()
}
