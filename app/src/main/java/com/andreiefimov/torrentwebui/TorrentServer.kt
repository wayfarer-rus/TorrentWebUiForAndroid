package com.andreiefimov.torrentwebui

import android.content.Context
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic

/**
 * Ktor HTTP server that serves the SvelteKit WebUI static assets and provides
 * the backbone for all subsequent REST API and WebSocket work.
 */
object TorrentServer {

    private const val TAG = "TorrentServer"

    /** Port the Ktor server binds to. Configurable for dev flexibility. */
    const val PORT: Int = 8080

    /** JSON serializer shared across all endpoints. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var server: Any? = null
    private lateinit var appContext: Context

    /** Auth manager — injected for testability. */
    private var authManager: AuthManager = DefaultAuthManager(null!!)

    /** Torrent session operations — injected for testability. Defaults to real TorrentSession. */
    internal var sessionOps: TorrentSessionOps = TorrentSession

    /**
     * Starts the Ktor server on [PORT] bound to 0.0.0.0.
     * Must be called before [stop]. Safe to call multiple times (idempotent).
     *
     * @param authManager optional auth manager; defaults to [DefaultAuthManager] backed by SharedPreferences.
     */
    fun start(context: Context, authManager: AuthManager? = null) {
        if (isRunning()) {
            Log.i(TAG, "Server already running on port $PORT")
            return
        }

        appContext = context.applicationContext
        this.authManager = authManager ?: DefaultAuthManager(appContext)
        Log.i(TAG, "Starting Ktor server on http://0.0.0.0:$PORT")

        // Use a lambda instead of top-level module function — Ktor's module function
        // discovery uses reflection and fails on Android (dex transformation).
        val eng = embeddedServer(Netty, PORT, "0.0.0.0", listOf()) { configureApplication(this) }
        eng.start(wait = false)
        server = eng

        Log.i(TAG, "Ktor server started successfully")
    }

    /**
     * Configures Ktor routing: auth middleware, static file serving from Android assets,
     * JSON content negotiation for REST API, plus WebSocket for live progress.
     * Public for testability — used by [testApplication] in unit tests via wrapper.
     */
    fun configureApplication(application: Application) {
        // JSON content negotiation — handles serialization/deserialization for all routes.
        application.install(ContentNegotiation) { json(this@TorrentServer.json) }

        // WebSocket plugin — required for /ws/progress.
        application.install(WebSockets) {
            // Ktor 3.x uses default ping/pong settings; no explicit configuration needed.
        }

        // Basic Authentication — protects all routes except /health and /ws/progress.
        application.install(io.ktor.server.auth.Authentication) {
            basic("webui") {
                validate { credentials ->
                    val storedPassword = authManager.getPassword()
                    if (credentials.password == storedPassword) {
                        WebUiPrincipal(storedPassword)
                    } else {
                        null
                    }
                }
            }
        }

        application.routing {
            // ---- Unauthenticated routes ----

            // Health check endpoint for diagnostics.
            get("/health") {
                Log.i(TAG, "Health check requested")
                try {
                    call.respond(HealthResponse("ok"))
                    Log.i(TAG, "Health check responded successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Health check failed", e)
                }
            }

            // WebSocket: live torrent progress + alerts (bypasses auth — page-level auth is the gate).
            webSocket("/ws/progress") {
                Log.i(TAG, "WebSocket client connected")

                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                try {
                    // Send an initial snapshot immediately.
                    send(Frame.Text(buildSnapshotJson()))
                    scope.launch {
                        try {
                            while (true) {
                                delay(1000L)
                                send(Frame.Text(buildSnapshotJson()))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "WebSocket snapshot error", e)
                        }
                    }

                    // Handle incoming messages (keep alive until client disconnects).
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            if (text == "ping") {
                                send(Frame.Text("pong"))
                            }
                        }
                    }
                } finally {
                    scope.cancel()
                    Log.i(TAG, "WebSocket client disconnected")
                }
            }

            // ---- Authenticated routes ----
            route("") {
                authenticate("webui") {
                    // Serve the WebUI entry point.
                    get("/") {
                        val html = assetReader("www/index.html") ?: return@get call.respondText(
                            "index.html not found", ContentType.Text.Plain, HttpStatusCode.InternalServerError
                        )
                        call.respondText(html, ContentType.Text.Html)
                    }

                    // Serve all static assets from the WebUI root (SvelteKit's /app-build/, etc.).
                    // Must come AFTER explicit routes above — catch-all matches everything.
                    get("/{path...}") {
                        val uri = call.request.local.uri
                        Log.i(TAG, "=== WILDCARD ROUTE MATCHED === URI: $uri")
                        // Extract full path from URI (skip leading /)
                        val fullPath = uri.removePrefix("/")
                        Log.i(TAG, "Static request URI: $uri, extracted path: '$fullPath'")
                        // Assets are stored under "www/" in the assets directory. The index.html route
                        // works with "www/index.html", and SvelteKit's build output is under "app-build/".
                        val assetPath = when {
                            fullPath == "index.html" -> "www/index.html"
                            else -> "www/$fullPath"
                        }
                        Log.i(TAG, "Attempting to read asset: $assetPath")
                        val content = assetReader(assetPath)
                            ?: return@get call.respondText(
                                "Not found (tried: $assetPath)", ContentType.Text.Plain, HttpStatusCode.NotFound
                            )
                        val contentType = detectContentType(assetPath)
                        call.respondText(content, contentType)
                    }

                    // Fallback: log all unmatched requests for debugging
                    get("/*") {
                        val uri = call.request.local.uri
                        Log.i(TAG, "=== FALLBACK ROUTE MATCHED === URI: $uri")
                        call.respondText("Fallback: $uri", ContentType.Text.Plain)
                    }

                    // ---- REST API: torrent management ----
                    route("/api/torrents") {
                        // POST /api/torrents/magnet — add a new torrent by magnet URI.
                        post("/magnet") {
                            val body = try {
                                call.receive<MagnetRequest>()
                            } catch (e: Exception) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse("Invalid request body: ${e.message}")
                                )
                                return@post
                            }

                            val magnetUri = body.magnet.trim()
                            if (magnetUri.isEmpty()) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse("Magnet URI is required")
                                )
                                return@post
                            }

                            val torrentId = sessionOps.addMagnet(magnetUri)
                            if (torrentId > 0) {
                                call.respond(MagnetResponse(id = torrentId, status = "ok"))
                            } else {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    ErrorResponse(sessionOps.lastError ?: "Failed to add magnet")
                                )
                            }
                        }

                        // GET /api/torrents — list all torrents with current state.
                        get {
                            val ids = sessionOps.getAllTorrentIds()
                            val torrents = ids.mapNotNull { id ->
                                sessionOps.getTorrentStatus(id)?.let { status ->
                                    TorrentListItem(
                                        id = status.id,
                                        name = status.name,
                                        state = status.state,
                                        progress = status.progress,
                                        downloadRate = status.downloadRate,
                                        uploadRate = status.uploadRate,
                                        peers = status.peers,
                                        savePath = status.savePath
                                    )
                                }
                            }
                            call.respond(torrents)
                        }

                        // PUT /api/torrents/{id}/pause — pause a torrent.
                        put("/{id}/pause") {
                            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                                Log.i(TAG, "Pause request for invalid ID: ${call.parameters["id"]}")
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                                return@put
                            }
                            Log.i(TAG, "Pause request for torrent $id")
                            if (sessionOps.pauseTorrent(id)) {
                                Log.i(TAG, "Pause successful for torrent $id")
                                call.respond(ControlResponse("ok"))
                            } else {
                                Log.i(TAG, "Pause failed for torrent $id")
                                call.respond(HttpStatusCode.NotFound, ErrorResponse("Torrent $id not found"))
                            }
                        }

                        // PUT /api/torrents/{id}/resume — resume a paused torrent.
                        put("/{id}/resume") {
                            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                                Log.i(TAG, "Resume request for invalid ID: ${call.parameters["id"]}")
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                                return@put
                            }
                            if (sessionOps.resumeTorrent(id)) {
                                call.respond(ControlResponse("ok"))
                            } else {
                                call.respond(HttpStatusCode.NotFound, ErrorResponse("Torrent $id not found"))
                            }
                        }

                        // DELETE /api/torrents/{id}?deleteFiles=true|false — remove a torrent.
                        delete("/{id}") {
                            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                                return@delete
                            }
                            val deleteFiles = call.request.queryParameters["deleteFiles"]?.toBooleanStrictOrNull() ?: false
                            if (sessionOps.removeTorrent(id, deleteFiles)) {
                                call.respond(ControlResponse("ok"))
                            } else {
                                call.respond(HttpStatusCode.NotFound, ErrorResponse("Torrent $id not found"))
                            }
                        }
                    }

                    // ---- REST API: settings ----
                    route("/api/settings") {
                        // POST /api/settings/password — change the WebUI password.
                        post("/password") {
                            val body = try {
                                call.receive<PasswordChangeRequest>()
                            } catch (e: Exception) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse("Invalid request body: ${e.message}")
                                )
                                return@post
                            }

                            // Validate current password.
                            val currentStored = authManager.getPassword()
                            if (body.currentPassword != currentStored) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse("Current password is incorrect")
                                )
                                return@post
                            }

                            // Validate new password.
                            val newPassword = body.newPassword.trim()
                            if (newPassword.length < 4) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse("New password must be at least 4 characters")
                                )
                                return@post
                            }

                            authManager.setPassword(newPassword)
                            Log.i(TAG, "Password changed successfully")
                            call.respond(PasswordChangeResponse(status = "ok"))
                        }
                    }
                }
            }
        }
    }

    /** Builds a JSON snapshot of all torrents and pending alerts. */
    private fun buildSnapshotJson(): String {
        val ids = sessionOps.getAllTorrentIds()
        val torrents = ids.mapNotNull { id ->
            sessionOps.getTorrentStatus(id)?.let { s ->
                TorrentListItem(
                    id = s.id, name = s.name, state = s.state, progress = s.progress,
                    downloadRate = s.downloadRate, uploadRate = s.uploadRate,
                    peers = s.peers, savePath = s.savePath
                )
            }
        }

        // Build the message envelope: {"type":"torrents","data":[...]}
        val torrentsJson = buildString {
            append("[")
            torrents.forEachIndexed { index, item ->
                if (index > 0) append(",")
                append("{\"id\":${item.id},\"name\":\"${escapeJson(item.name)}\",\"state\":\"${escapeJson(item.state)}\",\"progress\":${item.progress},\"downloadRate\":${item.downloadRate},\"uploadRate\":${item.uploadRate},\"peers\":${item.peers},\"savePath\":\"${escapeJson(item.savePath)}\"}")
            }
            append("]")
        }
        val envelope = """{"type":"torrents","data":$torrentsJson}"""

        // Append any pending alerts as separate messages.
        val alertJson = sessionOps.popAlerts()
        if (alertJson != "[]") {
            return envelope + alertJson
        }

        return envelope
    }

    /** Escapes special characters in a string for JSON encoding. */
    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Detects the MIME content type based on file extension.
     */
    private fun detectContentType(path: String): ContentType {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> ContentType.Text.Html
            "css" -> ContentType.Text.CSS
            "js" -> ContentType.Application.JavaScript
            "json" -> ContentType.Application.Json
            "png" -> ContentType.Image.PNG
            "jpg", "jpeg" -> ContentType.Image.JPEG
            "gif" -> ContentType.Image.GIF
            "svg" -> ContentType.Image.SVG
            "woff" -> ContentType("font", "woff")
            "woff2" -> ContentType("font", "woff2")
            "ttf" -> ContentType("font", "truetype")
            else -> ContentType.Application.OctetStream
        }
    }

    /** Stops the Ktor server. Safe to call if not running. */
    fun stop() {
        @Suppress("UNCHECKED_CAST")
        val s = server as? io.ktor.server.engine.EmbeddedServer<Any, Any>
        s?.stop()
        server = null
        Log.i(TAG, "Ktor server stopped")
    }

    /** Returns true if the server is currently running. */
    fun isRunning(): Boolean = server != null

    /** Asset reader function — overridable for unit tests. */
    internal var assetReader: (String) -> String? = ::readAssetFromAssets

    /**
     * Configures the server for unit testing. Sets mock dependencies so the Ktor
     * application can be started with [testApplication] without Android framework.
     */
    internal fun configureForTest(
        authManager: AuthManager,
        sessionOps: TorrentSessionOps,
        assetReader: (String) -> String? = { null }
    ) {
        this.authManager = authManager
        this.sessionOps = sessionOps
        this.assetReader = assetReader
    }

    /** Resets test configuration to production defaults. Call after each test. */
    internal fun resetToDefaults() {
        this.authManager = DefaultAuthManager(null!!)
        this.sessionOps = TorrentSession
        this.assetReader = ::readAssetFromAssets
    }

    /** Reads a file from Android assets. Returns null if the file doesn't exist. */
    private fun readAssetFromAssets(path: String): String? {
        return try {
            if (!::appContext.isInitialized) return null
            appContext.assets.open(path).bufferedReader().readText()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read asset: $path", e)
            null
        }
    }
}

/** Principal for authenticated WebUI requests. Username is ignored; password is the credential. */
class WebUiPrincipal(private val password: String)

// ---------------------------------------------------------------------------
// Request/Response DTOs for REST API
// ---------------------------------------------------------------------------

/** Request body for POST /api/torrents/magnet */
@Serializable
data class MagnetRequest(val magnet: String = "")

/** Response item for GET /api/torrents — mirrors TorrentStatus fields */
@Serializable
data class TorrentListItem(
    val id: Long,
    val name: String,
    val state: String,
    val progress: Float,
    val downloadRate: Long,
    val uploadRate: Long,
    val peers: Int,
    val savePath: String
)

/** Generic success response with status field. */
@Serializable
data class ControlResponse(val status: String)

/** Magnet add response with torrent ID. */
@Serializable
data class MagnetResponse(val id: Long, val status: String)

/** Health check response. */
@Serializable
data class HealthResponse(val status: String)

/** Error response with human-readable message. */
@Serializable
data class ErrorResponse(val error: String)

/** Request body for POST /api/settings/password */
@Serializable
data class PasswordChangeRequest(
    val currentPassword: String = "",
    val newPassword: String = ""
)

/** Response for successful password change. */
@Serializable
data class PasswordChangeResponse(val status: String)
