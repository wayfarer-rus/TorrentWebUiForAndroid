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

        application.routing {
            // Serve the WebUI entry point.
            get("/") {
                val html = readAsset("www/index.html") ?: return@get call.respondText(
                    "index.html not found", ContentType.Text.Plain, io.ktor.http.HttpStatusCode.InternalServerError
                )
                call.respondText(html, ContentType.Text.Html)
            }

            // Serve individual asset files from assets/www/.
            get("/assets/{path...}") {
                val path = call.parameters["path"] ?: return@get call.respondText(
                    "Not found", ContentType.Text.Plain, io.ktor.http.HttpStatusCode.NotFound
                )
                val content = readAsset("www/$path")
                    ?: return@get call.respondText(
                        "Not found", ContentType.Text.Plain, io.ktor.http.HttpStatusCode.NotFound
                    )
                val contentType = detectContentType("www/$path")
                call.respondText(content, contentType)
            }

            // Health check endpoint for diagnostics.
            get("/health") {
                call.respond(HealthResponse("ok"))
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
                put("/{id:\\d+}/pause") {
                    val id = call.parameters["id"]?.toLongOrNull() ?: run {
                        call.respond(io.ktor.http.HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                        return@put
                    }
                    if (TorrentSession.pauseTorrent(id)) {
                        call.respond(ControlResponse("ok"))
                    } else {
                        call.respond(io.ktor.http.HttpStatusCode.NotFound, ErrorResponse("Torrent $id not found"))
                    }
                }

                // PUT /api/torrents/{id}/resume — resume a paused torrent.
                put("/{id:\\d+}/resume") {
                    val id = call.parameters["id"]?.toLongOrNull() ?: run {
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
                delete("/{id:\\d+}") {
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
        }
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
