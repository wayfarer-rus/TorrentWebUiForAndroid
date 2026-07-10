package com.andreiefimov.torrentwebui

import android.content.Context
import android.util.Log
import io.ktor.http.ContentType
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

    /**
     * Starts the Ktor server on [PORT] bound to 0.0.0.0.
     * Must be called before [stop]. Safe to call multiple times (idempotent).
     */
    fun start(context: Context) {
        if (isRunning()) {
            Log.i(TAG, "Server already running on port $PORT")
            return
        }

        appContext = context.applicationContext
        Log.i(TAG, "Starting Ktor server on http://0.0.0.0:$PORT")

        // Use a lambda instead of ::configureApplication — Ktor's module function
        // discovery uses reflection and fails on Android (dex transformation).
        val eng = embeddedServer(Netty, PORT, "0.0.0.0", listOf()) { configureApplication(this) }
        eng.start(wait = false)
        server = eng

        Log.i(TAG, "Ktor server started successfully")
    }

    /**
     * Configures Ktor routing: static file serving from Android assets,
     * JSON content negotiation for REST API, plus placeholder routes for future endpoints.
     * Must be non-private — Ktor uses reflection to invoke this from EmbeddedServer.
     */
    internal fun configureApplication(application: Application) {
        // JSON content negotiation — handles serialization/deserialization for all routes.
        application.install(ContentNegotiation) { json(this@TorrentServer.json) }

        // WebSocket plugin — required for /ws/progress.
        application.install(WebSockets) {
            // Ktor 3.x uses default ping/pong settings; no explicit configuration needed.
        }

        application.routing {
            // Health check endpoint for diagnostics. Must come before the catch-all route.
            get("/health") {
                Log.i(TAG, "Health check requested")
                try {
                    call.respond(HealthResponse("ok"))
                    Log.i(TAG, "Health check responded successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Health check failed", e)
                }
            }

            // Serve the WebUI entry point.
            get("/") {
                val html = readAsset("www/index.html") ?: return@get call.respondText(
                    "index.html not found", ContentType.Text.Plain, io.ktor.http.HttpStatusCode.InternalServerError
                )
                call.respondText(html, ContentType.Text.Html)
            }

            // Serve all static assets from the WebUI root (SvelteKit's /_app/, etc.).
            // Must come AFTER explicit routes above — catch-all matches everything.
            get("/{path...}") {
                val uri = call.request.local.uri
                Log.i(TAG, "=== WILDCARD ROUTE MATCHED === URI: $uri")
                // Extract full path from URI (skip leading /)
                val fullPath = uri.removePrefix("/")
                Log.i(TAG, "Static request URI: $uri, extracted path: '$fullPath'")
                val assetPath = "www/$fullPath"
                Log.i(TAG, "Attempting to read asset: $assetPath")
                val content = readAsset(assetPath)
                    ?: return@get call.respondText(
                        "Not found (tried: $assetPath)", ContentType.Text.Plain, io.ktor.http.HttpStatusCode.NotFound
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
                            io.ktor.http.HttpStatusCode.BadRequest,
                            ErrorResponse("Invalid request body: ${e.message}")
                        )
                        return@post
                    }

                    val magnetUri = body.magnet.trim()
                    if (magnetUri.isEmpty()) {
                        call.respond(
                            io.ktor.http.HttpStatusCode.BadRequest,
                            ErrorResponse("Magnet URI is required")
                        )
                        return@post
                    }

                    val torrentId = TorrentSession.addMagnet(magnetUri)
                    if (torrentId > 0) {
                        call.respond(MagnetResponse(id = torrentId, status = "ok"))
                    } else {
                        call.respond(
                            io.ktor.http.HttpStatusCode.InternalServerError,
                            ErrorResponse(TorrentSession.lastError ?: "Failed to add magnet")
                        )
                    }
                }

                // GET /api/torrents — list all torrents with current state.
                get {
                    val ids = TorrentSession.getAllTorrentIds()
                    val torrents = ids.mapNotNull { id ->
                        TorrentSession.getTorrentStatus(id)?.let { status ->
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
                        call.respond(io.ktor.http.HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                        return@put
                    }
                    Log.i(TAG, "Pause request for torrent $id")
                    if (TorrentSession.pauseTorrent(id)) {
                        Log.i(TAG, "Pause successful for torrent $id")
                        call.respond(ControlResponse("ok"))
                    } else {
                        Log.i(TAG, "Pause failed for torrent $id")
                        call.respond(io.ktor.http.HttpStatusCode.NotFound, ErrorResponse("Torrent $id not found"))
                    }
                }

                // PUT /api/torrents/{id}/resume — resume a paused torrent.
                put("/{id}/resume") {
                    val id = call.parameters["id"]?.toLongOrNull() ?: run {
                        Log.i(TAG, "Resume request for invalid ID: ${call.parameters["id"]}")
                        call.respond(io.ktor.http.HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                        return@put
                    }
                    if (TorrentSession.resumeTorrent(id)) {
                        call.respond(ControlResponse("ok"))
                    } else {
                        call.respond(io.ktor.http.HttpStatusCode.NotFound, ErrorResponse("Torrent $id not found"))
                    }
                }

                // DELETE /api/torrents/{id}?deleteFiles=true|false — remove a torrent.
                delete("/{id}") {
                    val id = call.parameters["id"]?.toLongOrNull() ?: run {
                        call.respond(io.ktor.http.HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                        return@delete
                    }
                    val deleteFiles = call.request.queryParameters["deleteFiles"]?.toBooleanStrictOrNull() ?: false
                    if (TorrentSession.removeTorrent(id, deleteFiles)) {
                        call.respond(ControlResponse("ok"))
                    } else {
                        call.respond(io.ktor.http.HttpStatusCode.NotFound, ErrorResponse("Torrent $id not found"))
                    }
                }
            }

            // ---- WebSocket: live torrent progress + alerts ----
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
        }
    }

    /** Builds a JSON snapshot of all torrents and pending alerts. */
    private fun buildSnapshotJson(): String {
        val ids = TorrentSession.getAllTorrentIds()
        val torrents = ids.mapNotNull { id ->
            TorrentSession.getTorrentStatus(id)?.let { s ->
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
        val alertJson = TorrentSession.popAlerts()
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
     * Reads a file from Android assets. Returns null if the file doesn't exist.
     */

    private fun readAsset(path: String): String? {
        return try {
            appContext.assets.open(path).bufferedReader().readText()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read asset: $path", e)
            null
        }
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
}

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
