package com.andreiefimov.torrentwebui

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingApiTest {

    @After
    fun tearDown() {
        TorrentServer.resetToDefaults()
    }

    @Test
    fun `status is authenticated and exposes only consumer onboarding fields`() = testApplication {
        configure(incompleteCoordinator(readiness = OnboardingReadiness.ServiceUnavailable))

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/onboarding/status").status)

        val response = client.get("/api/onboarding/status") {
            header(HttpHeaders.Authorization, basic(WebUiCredentials.DEFAULT_PASSWORD))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val status = Json.decodeFromString<OnboardingStatus>(response.bodyAsText())
        assertFalse(status.completed)
        assertEquals(PasswordDecision.Pending, status.passwordDecision)
        assertFalse(status.hasApprovedDestination)
        assertEquals(OnboardingReadiness.ServiceUnavailable, status.readiness)
        assertFalse(response.bodyAsText().contains("lifecycle", ignoreCase = true))
        assertFalse(response.bodyAsText().contains("permission", ignoreCase = true))

        val storageRecovery = client.get("/api/storage/permission") {
            header(HttpHeaders.Authorization, basic(WebUiCredentials.DEFAULT_PASSWORD))
        }
        assertEquals(HttpStatusCode.OK, storageRecovery.status)
    }

    @Test
    fun `authenticated normal torrent APIs are rejected before onboarding completion`() = testApplication {
        configure(incompleteCoordinator())

        val normalTorrentApis = listOf(
            HttpMethod.Get to "/api/torrents",
            HttpMethod.Get to "/api/torrents/1/destination",
            HttpMethod.Get to "/api/torrents/1/move/status",
            HttpMethod.Post to "/api/torrents/magnet",
            HttpMethod.Put to "/api/torrents/1/pause",
            HttpMethod.Put to "/api/torrents/1/resume",
            HttpMethod.Post to "/api/torrents/1/move",
            HttpMethod.Post to "/api/torrents/1/move/cancel",
            HttpMethod.Post to "/api/torrents/1/move/retry",
            HttpMethod.Delete to "/api/torrents/1"
        )

        normalTorrentApis.forEach { (method, path) ->
            val response = client.request(path) {
                this.method = method
                header(HttpHeaders.Authorization, basic(WebUiCredentials.DEFAULT_PASSWORD))
            }
            assertEquals(path, HttpStatusCode.Conflict, response.status)
            assertTrue(path, response.bodyAsText().contains("onboarding_incomplete"))
        }
    }

    @Test
    fun `completed onboarding does not apply the onboarding mutation rejection`() = testApplication {
        configure(OnboardingCoordinator.completedForTest())

        val response = client.post("/api/torrents/magnet") {
            header(HttpHeaders.Authorization, basic(WebUiCredentials.DEFAULT_PASSWORD))
        }

        assertTrue(response.status != HttpStatusCode.Conflict ||
            !response.bodyAsText().contains("onboarding_incomplete"))
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.configure(
        onboarding: OnboardingCoordinator
    ) {
        TorrentServer.configureForTest(
            authManager = InMemoryAuthManager(),
            daemonControl = RecoveryBlockedDaemonControl,
            onboardingCoordinator = onboarding,
            assetReader = { path -> if (path == "www/index.html") "<html>WebUI</html>" else null }
        )
        application { TorrentServer.configureApplication(this) }
    }

    private fun incompleteCoordinator(
        readiness: OnboardingReadiness = OnboardingReadiness.Ready
    ) = OnboardingCoordinator(
        store = object : OnboardingStateStore {
            private var record: OnboardingRecord? = OnboardingRecord(false, PasswordDecision.Pending)
            override fun read(): OnboardingRecord? = record
            override fun write(record: OnboardingRecord): Boolean {
                this.record = record
                return true
            }
        },
        hasDurableQueue = { false },
        hasApprovedDestination = { false },
        hasNonDefaultPassword = { false },
        readiness = { readiness }
    )

    private fun basic(password: String): String {
        val encoded = Base64.getEncoder().encodeToString("browser:$password".toByteArray())
        return "Basic $encoded"
    }
}
