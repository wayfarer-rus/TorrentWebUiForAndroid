package com.andreiefimov.torrentwebui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebUiPortPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearPortPreferences() {
        assertEquals(
            true,
            context.getSharedPreferences(
            SharedPreferencesWebUiPortStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
                .edit()
                .clear()
                .commit()
        )
    }

    @Test
    fun defaultAndCommittedPortSurviveStoreReconstruction() {
        val firstProcessStore = SharedPreferencesWebUiPortStore(context)
        assertEquals(WebUiPort.DEFAULT, firstProcessStore.read())

        assertEquals(true, firstProcessStore.write(9090))

        val reconstructedStore = SharedPreferencesWebUiPortStore(context)
        assertEquals(9090, reconstructedStore.read())
    }

    @Test
    fun invalidPersistedValueIsNotAcceptedAsConfiguration() {
        val preferences = context.getSharedPreferences(
            SharedPreferencesWebUiPortStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        assertEquals(
            true,
            preferences.edit()
                .putInt(SharedPreferencesWebUiPortStore.KEY_CONFIGURED_PORT, 80)
                .commit()
        )

        val reconstructedStore = SharedPreferencesWebUiPortStore(context)

        assertEquals(WebUiPort.DEFAULT, reconstructedStore.read())
        assertFalse(WebUiPort.isValid(80))
    }
}
