package com.andreiefimov.torrentwebui

import android.content.Context
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Ktor HTTP server that serves the SvelteKit WebUI static assets and provides
 * the backbone for all subsequent REST API and WebSocket work.
 */
object TorrentServer {

    private const val TAG = "TorrentServer"

    /** Port the Ktor server binds to. Configurable for dev flexibility. */
    const val PORT: Int = 8080

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

        val eng = embeddedServer(Netty, PORT, "0.0.0.0", listOf(), ::configureApplication)
        eng.start(wait = false)
        server = eng

        Log.i(TAG, "Ktor server started successfully")
    }

    /**
     * Configures Ktor routing: static file serving from Android assets,
     * plus placeholder routes for future REST API endpoints.
     */
    private fun configureApplication(application: Application) {
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
                call.respondText(
                    """{"status":"ok"}""",
                    ContentType.Application.Json
                )
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
