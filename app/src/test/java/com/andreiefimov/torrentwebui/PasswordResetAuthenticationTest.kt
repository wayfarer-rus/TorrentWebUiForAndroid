package com.andreiefimov.torrentwebui

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordResetAuthenticationTest {

    @After
    fun tearDown() {
        TorrentServer.resetToDefaults()
    }

    @Test
    fun `confirmed Android reset invalidates old browser credentials on next request`() = testApplication {
        val auth = InMemoryAuthManager("forgotten-password")
        TorrentServer.configureForTest(
            authManager = auth,
            daemonControl = RecoveryBlockedDaemonControl,
            assetReader = { path -> if (path == "www/index.html") "<html>WebUI</html>" else null }
        )
        application { TorrentServer.configureApplication(this) }

        assertEquals(HttpStatusCode.OK, client.get("/") {
            header(HttpHeaders.Authorization, basic("forgotten-password"))
        }.status)

        val reset = PasswordResetController(auth)
        reset.requestConfirmation()
        reset.confirm()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/") {
            header(HttpHeaders.Authorization, basic("forgotten-password"))
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/") {
            header(HttpHeaders.Authorization, basic(WebUiCredentials.DEFAULT_PASSWORD))
        }.status)
    }

    private fun basic(password: String): String {
        val encoded = Base64.getEncoder().encodeToString("browser:$password".toByteArray())
        return "Basic $encoded"
    }
}
