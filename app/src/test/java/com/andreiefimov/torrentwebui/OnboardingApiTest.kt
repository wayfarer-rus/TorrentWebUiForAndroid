package com.andreiefimov.torrentwebui

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
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
    fun `recommended destination proposal and confirmation update resumable status`() = testApplication {
        var destinationPresent = false
        val operations = object : RecommendedDestinationOperations {
            override fun proposal() = RecommendedDestinationResult.Success(
                "/storage/primary/Download/Torrents"
            )

            override suspend fun confirm(): RecommendedDestinationResult {
                destinationPresent = true
                return proposal()
            }
        }
        configure(
            onboarding = incompleteCoordinator(hasDestination = { destinationPresent }),
            recommendedDestinationOperations = operations
        )

        val proposal = client.get("/api/onboarding/recommended-destination") {
            header(HttpHeaders.Authorization, basic(WebUiCredentials.DEFAULT_PASSWORD))
        }
        assertEquals(HttpStatusCode.OK, proposal.status)
        assertTrue(proposal.bodyAsText().contains("/storage/primary/Download/Torrents"))

        val confirmed = client.post("/api/onboarding/recommended-destination") {
            header(HttpHeaders.Authorization, basic(WebUiCredentials.DEFAULT_PASSWORD))
        }
        assertEquals(HttpStatusCode.OK, confirmed.status)

        val status = client.get("/api/onboarding/status") {
            header(HttpHeaders.Authorization, basic(WebUiCredentials.DEFAULT_PASSWORD))
        }
        assertTrue(Json.decodeFromString<OnboardingStatus>(status.bodyAsText()).hasApprovedDestination)
    }

    @Test
    fun `unavailable alternate destination cannot be browsed and keeps onboarding incomplete`() = testApplication {
        val storage = object : StorageApiOperations {
            override fun volumes() = listOf(StorageVolume("/storage/USB", isRemovable = true))
            override fun children(parentPath: String) =
                DirectoryChildrenResult.Unavailable("This storage volume is no longer available.")
            override fun validate(path: String) =
                DirectoryValidationResult.rejected(path, "This folder cannot be used.")
            override suspend fun approve(path: String) =
                DestinationApprovalResult.Rejected("This folder cannot be used.")
        }
        configure(
            onboarding = incompleteCoordinator(),
            storageApiOperations = storage
        )

        val response = client.get("/api/storage/children/${encode("/storage/USB")}") {
            header(HttpHeaders.Authorization, basic(WebUiCredentials.DEFAULT_PASSWORD))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("no longer available"))
        val status = client.get("/api/onboarding/status") {
            header(HttpHeaders.Authorization, basic(WebUiCredentials.DEFAULT_PASSWORD))
        }
        assertFalse(Json.decodeFromString<OnboardingStatus>(status.bodyAsText()).completed)
    }

    @Test
    fun `alternate destination APIs preserve backend volume metadata and canonical approval`() = testApplication {
        var approvalInput: String? = null
        val storage = object : StorageApiOperations {
            override fun volumes() = listOf(
                StorageVolume("/storage/primary", isPrimary = true),
                StorageVolume("/storage/USB", isRemovable = true)
            )
            override fun children(parentPath: String) = DirectoryChildrenResult.Available(
                listOf("$parentPath/Movies")
            )
            override fun validate(path: String) = when (path) {
                "/storage/USB/Alias" -> DirectoryValidationResult.valid(path, "/storage/USB/Movies")
                else -> DirectoryValidationResult.rejected(path, "This folder cannot be used.")
            }
            override suspend fun approve(path: String): DestinationApprovalResult {
                approvalInput = path
                return DestinationApprovalResult.Approved("/storage/USB/Movies")
            }
        }
        configure(incompleteCoordinator(), storageApiOperations = storage)
        val authorization = basic(WebUiCredentials.DEFAULT_PASSWORD)

        val volumes = client.get("/api/storage/volumes") {
            header(HttpHeaders.Authorization, authorization)
        }
        assertEquals(HttpStatusCode.OK, volumes.status)
        assertTrue(volumes.bodyAsText().contains("/storage/primary"))
        assertTrue(volumes.bodyAsText().contains("/storage/USB"))
        assertTrue(volumes.bodyAsText().contains("isRemovable\":true"))

        val validation = client.post("/api/storage/validate") {
            header(HttpHeaders.Authorization, authorization)
            header(HttpHeaders.ContentType, "application/json")
            setBody("{\"path\":\"/storage/USB/Alias\"}")
        }
        assertEquals(HttpStatusCode.OK, validation.status)
        assertTrue(validation.bodyAsText().contains("/storage/USB/Movies"))

        val rejected = client.post("/api/storage/validate") {
            header(HttpHeaders.Authorization, authorization)
            header(HttpHeaders.ContentType, "application/json")
            setBody("{\"path\":\"content://documents/tree/USB\"}")
        }
        assertTrue(rejected.bodyAsText().contains("cannot be used"))
        assertFalse(rejected.bodyAsText().contains("\"isValid\":true"))

        val approved = client.post("/api/storage/destinations/${encode("/storage/USB/Movies")}") {
            header(HttpHeaders.Authorization, authorization)
        }
        assertEquals(HttpStatusCode.OK, approved.status)
        assertEquals("/storage/USB/Movies", approvalInput)
        assertTrue(approved.bodyAsText().contains("/storage/USB/Movies"))
    }

    @Test
    fun `onboarding password change completes and invalidates stale credentials`() = testApplication {
        val auth = InMemoryAuthManager("old-password")
        configure(
            onboarding = incompleteCoordinator(hasDestination = { true }),
            authManager = auth
        )

        val changed = client.post("/api/onboarding/password") {
            header(HttpHeaders.Authorization, basic("old-password"))
            header(HttpHeaders.ContentType, "application/json")
            setBody("{\"newPassword\":\"household passphrase\"}")
        }

        assertEquals(HttpStatusCode.OK, changed.status)
        val status = Json.decodeFromString<OnboardingStatus>(changed.bodyAsText())
        assertTrue(status.completed)
        assertEquals(PasswordDecision.Changed, status.passwordDecision)
        assertEquals("household passphrase", auth.getPassword())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/onboarding/status") {
            header(HttpHeaders.Authorization, basic("old-password"))
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/onboarding/status") {
            header(HttpHeaders.Authorization, basic("household passphrase"))
        }.status)
    }

    @Test
    fun `onboarding password endpoint rejects short extra-field and completed requests`() = testApplication {
        val auth = InMemoryAuthManager("old-password")
        configure(
            onboarding = incompleteCoordinator(hasDestination = { true }),
            authManager = auth
        )

        val short = client.post("/api/onboarding/password") {
            header(HttpHeaders.Authorization, basic("old-password"))
            header(HttpHeaders.ContentType, "application/json")
            setBody("{\"newPassword\":\"abc\"}")
        }
        assertEquals(HttpStatusCode.BadRequest, short.status)
        assertTrue(short.bodyAsText().contains("at least 4 characters"))

        val extraField = client.post("/api/onboarding/password") {
            header(HttpHeaders.Authorization, basic("old-password"))
            header(HttpHeaders.ContentType, "application/json")
            setBody("{\"currentPassword\":\"old-password\",\"newPassword\":\"valid-password\"}")
        }
        assertEquals(HttpStatusCode.BadRequest, extraField.status)
        assertEquals("old-password", auth.getPassword())
    }

    @Test
    fun `onboarding password persistence failure keeps prior credential and pending decision`() = testApplication {
        val auth = InMemoryAuthManager("old-password", persistenceSucceeds = false)
        configure(
            onboarding = incompleteCoordinator(hasDestination = { true }),
            authManager = auth
        )

        val response = client.post("/api/onboarding/password") {
            header(HttpHeaders.Authorization, basic("old-password"))
            header(HttpHeaders.ContentType, "application/json")
            setBody("{\"newPassword\":\"valid-password\"}")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("old-password", auth.getPassword())
        val status = client.get("/api/onboarding/status") {
            header(HttpHeaders.Authorization, basic("old-password"))
        }
        assertEquals(PasswordDecision.Pending, Json.decodeFromString<OnboardingStatus>(status.bodyAsText()).passwordDecision)
    }

    @Test
    fun `completed onboarding rejects onboarding password change`() = testApplication {
        val auth = InMemoryAuthManager("old-password")
        configure(OnboardingCoordinator.completedForTest(), authManager = auth)

        val response = client.post("/api/onboarding/password") {
            header(HttpHeaders.Authorization, basic("old-password"))
            header(HttpHeaders.ContentType, "application/json")
            setBody("{\"newPassword\":\"valid-password\"}")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("old-password", auth.getPassword())
    }

    @Test
    fun `normal password endpoint still requires current and new passwords`() = testApplication {
        val auth = InMemoryAuthManager("old-password")
        configure(OnboardingCoordinator.completedForTest(), authManager = auth)

        val missingCurrent = client.post("/api/settings/password") {
            header(HttpHeaders.Authorization, basic("old-password"))
            header(HttpHeaders.ContentType, "application/json")
            setBody("{\"newPassword\":\"valid-password\"}")
        }
        assertEquals(HttpStatusCode.BadRequest, missingCurrent.status)
        assertEquals("old-password", auth.getPassword())

        val changed = client.post("/api/settings/password") {
            header(HttpHeaders.Authorization, basic("old-password"))
            header(HttpHeaders.ContentType, "application/json")
            setBody("{\"currentPassword\":\"old-password\",\"newPassword\":\"valid-password\"}")
        }
        assertEquals(HttpStatusCode.OK, changed.status)
        assertEquals("valid-password", auth.getPassword())
    }

    @Test
    fun `password deferral completes eligible onboarding without changing password`() = testApplication {
        val auth = InMemoryAuthManager("unchanged-password")
        configure(
            onboarding = incompleteCoordinator(hasDestination = { true }),
            authManager = auth
        )

        val response = client.post("/api/onboarding/password/defer") {
            header(HttpHeaders.Authorization, basic("unchanged-password"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val status = Json.decodeFromString<OnboardingStatus>(response.bodyAsText())
        assertTrue(status.completed)
        assertEquals(PasswordDecision.Deferred, status.passwordDecision)
        assertEquals("unchanged-password", auth.getPassword())
    }

    @Test
    fun `recommended destination confirmation is rejected until readiness is ready`() = testApplication {
        var confirmationCalls = 0
        val operations = object : RecommendedDestinationOperations {
            override fun proposal() = RecommendedDestinationResult.Success("/storage/primary/Download/Torrents")
            override suspend fun confirm(): RecommendedDestinationResult {
                confirmationCalls += 1
                return proposal()
            }
        }
        configure(
            onboarding = incompleteCoordinator(
                readiness = OnboardingReadiness.ServiceUnavailable
            ),
            recommendedDestinationOperations = operations
        )

        val response = client.post("/api/onboarding/recommended-destination") {
            header(HttpHeaders.Authorization, basic(WebUiCredentials.DEFAULT_PASSWORD))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(0, confirmationCalls)
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
        onboarding: OnboardingCoordinator,
        recommendedDestinationOperations: RecommendedDestinationOperations? = null,
        storageApiOperations: StorageApiOperations? = null,
        authManager: AuthManager = InMemoryAuthManager()
    ) {
        TorrentServer.configureForTest(
            authManager = authManager,
            daemonControl = RecoveryBlockedDaemonControl,
            onboardingCoordinator = onboarding,
            recommendedDestinationOperations = recommendedDestinationOperations,
            storageApiOperations = storageApiOperations,
            assetReader = { path -> if (path == "www/index.html") "<html>WebUI</html>" else null }
        )
        application { TorrentServer.configureApplication(this) }
    }

    private fun incompleteCoordinator(
        readiness: OnboardingReadiness = OnboardingReadiness.Ready,
        hasDestination: () -> Boolean = { false }
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
        hasApprovedDestination = { hasDestination() },
        hasNonDefaultPassword = { false },
        readiness = { readiness }
    )

    private fun encode(path: String): String = java.net.URLEncoder.encode(path, Charsets.UTF_8)

    private fun basic(password: String): String {
        val encoded = Base64.getEncoder().encodeToString("browser:$password".toByteArray())
        return "Basic $encoded"
    }
}
