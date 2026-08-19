package com.andreiefimov.torrentwebui

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StaticAssetRoutingTest {

    @After
    fun tearDown() {
        TorrentServer.resetToDefaults()
    }

    @Test
    fun `static lookup excludes query values and missing responses disclose no asset path`() =
        testApplication {
            val requestedAssets = mutableListOf<String>()
            TorrentServer.configureForTest(
                authManager = InMemoryAuthManager("test-password"),
                daemonControl = RecoveryBlockedDaemonControl,
                assetReader = { path ->
                    requestedAssets += path
                    if (path == "www/app-build/app.js") "console.log('ok')" else null
                }
            )
            application { TorrentServer.configureApplication(this) }

            val asset = client.get("/app-build/app.js?cacheBust=private-value") {
                header(HttpHeaders.Authorization, basic("test-password"))
            }
            assertEquals(HttpStatusCode.OK, asset.status)
            assertEquals(listOf("www/app-build/app.js"), requestedAssets)

            val missing = client.get("/missing.js?cacheBust=private-value") {
                header(HttpHeaders.Authorization, basic("test-password"))
            }
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals("Not found", missing.body<String>())
            assertFalse(missing.body<String>().contains("missing.js"))
            assertFalse(missing.body<String>().contains("private-value"))
        }

    private fun basic(password: String): String {
        val encoded = Base64.getEncoder().encodeToString("browser:$password".toByteArray())
        return "Basic $encoded"
    }
}
