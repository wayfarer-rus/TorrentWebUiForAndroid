package com.andreiefimov.torrentwebui

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupPermissionGateTest {

    @Test
    fun `notification permission is requested first`() {
        assertEquals(
            StartupGateAction.RequestNotificationPermission,
            nextStartupGateAction(notificationGranted = false, storageGranted = false)
        )
    }

    @Test
    fun `storage permission is requested after notification permission`() {
        assertEquals(
            StartupGateAction.RequestStoragePermission,
            nextStartupGateAction(notificationGranted = true, storageGranted = false)
        )
    }

    @Test
    fun `daemon starts only after both permissions`() {
        assertEquals(
            StartupGateAction.StartDaemon,
            nextStartupGateAction(notificationGranted = true, storageGranted = true)
        )
    }
}
