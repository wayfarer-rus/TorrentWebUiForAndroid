package com.andreiefimov.torrentwebui.events

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertCorrelationTest {

    @Test
    fun `stable native torrent id correlates v2 alert without a v1 hash`() {
        val v2OnlyAlert =
            """{"type":"torrent_checked_alert","torrent_id":42,"info_hash":""}"""

        assertEquals(42L, nativeTorrentIdFromAlertJson(v2OnlyAlert))
    }

    @Test
    fun `missing native torrent id retains legacy hash fallback sentinel`() {
        val legacyAlert =
            """{"type":"torrent_checked_alert","info_hash":"0123456789abcdef"}"""

        assertEquals(-1L, nativeTorrentIdFromAlertJson(legacyAlert))
    }
}
