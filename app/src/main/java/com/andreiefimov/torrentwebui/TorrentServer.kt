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
import java.io.File
import java.security.MessageDigest

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
    private var authManager: AuthManager = object : AuthManager {
        @Volatile private var password: String = "start123"
        override fun getPassword(): String = password
        override fun setPassword(newPassword: String) { password = newPassword }
    }

    /**
     * Daemon control seam — injected for testability. Defaults to the production [TorrentSession].
     *
     * This is the unified seam that provides both session operations and lifecycle management.
     * Future milestones swap in a foreground-service-backed implementation without changing callers.
     */
    internal var daemonControl: DaemonControl = TorrentSession

    /**
     * Destination catalog — injected for testability. Defaults to a file-backed catalog
     * rooted in app-private configuration.
     */
    internal var destinationCatalog: DestinationCatalog? = null

    /**
     * Queue store reference — used by destination removal to check for references.
     */
    internal var queueStore: QueueStore? = null

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
            basic("Torrent WebUI") {
                validate { credentials ->
                    val storedPassword = authManager.getPassword()
                    if (constantTimeEquals(credentials.password, storedPassword)) {
                        WebUiPrincipal
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
                authenticate("Torrent WebUI") {
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

                    // ---- REST API: storage (Milestone 4) ----
                    route("/api/storage") {
                        // GET /api/storage/volumes — returns reported storage volume roots.
                        get("/volumes") {
                            val volumes = StorageVolumeService.listVolumes(appContext)
                            call.respond(volumes.map { VolumeResponse(it.path, it.description, it.isRemovable) })
                        }

                        // GET /api/storage/children/{path} — lists validated child directories.
                        get("/children/{path}") {
                            val parentPath = call.parameters["path"] ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing path parameter"))
                                return@get
                            }
                            val children = DirectoryValidationService.listValidChildren(appContext, parentPath)
                            call.respond(children)
                        }

                        // POST /api/storage/validate — validates a pasted absolute path.
                        post("/validate") {
                            val body = try {
                                call.receive<ValidateRequest>()
                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                                return@post
                            }
                            val result = DirectoryValidationService.validate(appContext, body.path)
                            call.respond(ValidateResponse(
                                path = result.path,
                                canonicalPath = result.canonicalPath,
                                isValid = result.isValid,
                                rejectionReason = result.rejectionReason
                            ))
                        }

                        // GET /api/storage/catalog — lists all approved destinations.
                        get("/catalog") {
                            val catalog = TorrentServer.destinationCatalog
                                ?: run { call.respond(emptyList<String>()); return@get }
                            val destinations = catalog.listDestinations()
                            call.respond(destinations)
                        }

                        // POST /api/storage/destinations/{path} — adds a validated path to the catalog.
                        post("/destinations/{path}") {
                            val destPath = call.parameters["path"] ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing path parameter"))
                                return@post
                            }
                            val catalog = TorrentServer.destinationCatalog
                                ?: run { call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Storage service unavailable")); return@post }

                            // Validate first.
                            val validation = DirectoryValidationService.validate(appContext, destPath)
                            if (!validation.isValid) {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse(validation.rejectionReason ?: "Invalid path"))
                                return@post
                            }

                            val added = catalog.addDestination(validation.canonicalPath!!)
                            if (added) {
                                call.respond(AddDestinationResponse(status = "ok", path = validation.canonicalPath))
                            } else {
                                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to add destination"))
                            }
                        }

                        // DELETE /api/storage/destinations/{path} — removes a destination (only if unreferenced).
                        delete("/destinations/{path}") {
                            val destPath = call.parameters["path"] ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing path parameter"))
                                return@delete
                            }
                            val catalog = TorrentServer.destinationCatalog
                                ?: run { call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Storage service unavailable")); return@delete }
                            val queueStore = TorrentServer.queueStore
                                ?: run { call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Queue store unavailable")); return@delete }

                            val removed = catalog.removeDestination(destPath, queueStore)
                            if (removed) {
                                call.respond(ControlResponse("ok"))
                            } else {
                                val queue = queueStore.loadQueueIntent()
                                if (queue.any { it.destinationPath == destPath }) {
                                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Destination is still referenced by a queue entry"))
                                } else {
                                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Destination not found in catalog"))
                                }
                            }
                        }

                        // GET /api/storage/latest-selected — returns the latest selected destination.
                        get("/latest-selected") {
                            val catalog = TorrentServer.destinationCatalog
                                ?: run { call.respond(LatestSelectedResponse(null)); return@get }
                            val latest = catalog.getLatestSelected()
                            call.respond(LatestSelectedResponse(latest))
                        }

                        // GET /api/storage/permission — returns current storage permission state.
                        get("/permission") {
                            val state = TorrentServer.daemonControl.storagePermissionState
                            call.respond(StoragePermissionResponse(state.name))
                        }
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

                            // Determine destination: use provided, or default to latest-selected.
                            var destinationPath = body.destinationPath?.trim()
                            val catalog = TorrentServer.destinationCatalog

                            if (destinationPath == null || destinationPath.isEmpty()) {
                                // Default to latest-selected.
                                if (catalog != null) {
                                    destinationPath = catalog.getLatestSelected()
                                }
                            } else {
                                // Validate the provided destination.
                                if (isLegacySavePath(destinationPath, TorrentDaemon.getLegacySaveDirectory(appContext)?.absolutePath)) {
                                    call.respond(
                                        HttpStatusCode.BadRequest,
                                        ErrorResponse("Cannot use legacy save directory as destination for new torrents")
                                    )
                                    return@post
                                }

                                val validation = DirectoryValidationService.validate(appContext, destinationPath)
                                if (!validation.isValid) {
                                    call.respond(
                                        HttpStatusCode.BadRequest,
                                        ErrorResponse(validation.rejectionReason ?: "Invalid destination path")
                                    )
                                    return@post
                                }

                                // Ensure it's in the catalog.
                                if (catalog != null && !catalog.contains(validation.canonicalPath!!)) {
                                    catalog.addDestination(validation.canonicalPath!!)
                                }
                            }

                            // Block storage operations when permission is unavailable.
                            if (!TorrentServer.daemonControl.isStorageReady) {
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    ErrorResponse("Storage permission required. Grant All Files Access in system settings and restart downloads.")
                                )
                                return@post
                            }

                            // Re-check at operation time to catch runtime revocation.
                            TorrentServer.daemonControl.refreshStoragePermissionState(appContext)
                            if (!TorrentServer.daemonControl.isStorageReady) {
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    ErrorResponse("Storage permission required. Grant All Files Access in system settings and restart downloads.")
                                )
                                return@post
                            }

                            val torrentId = daemonControl.addMagnet(magnetUri)
                            if (torrentId > 0) {
                                // Persist the destination in the queue.
                                if (destinationPath != null && TorrentServer.queueStore != null) {
                                    val queue = TorrentServer.queueStore!!.loadQueueIntent()
                                    val updatedQueue = queue + QueueEntry(
                                        magnetUri = magnetUri,
                                        isPaused = false,
                                        destinationPath = destinationPath
                                    )
                                    TorrentServer.queueStore!!.saveQueueIntent(updatedQueue)
                                }

                                call.respond(MagnetResponse(id = torrentId, status = "ok"))
                            } else {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    ErrorResponse(daemonControl.lastError ?: "Failed to add magnet")
                                )
                            }
                        }

                        // GET /api/torrents — list all torrents with current state.
                        get {
                            val ids = daemonControl.getAllTorrentIds()
                            // Note: per-torrent destination matching requires a magnetUri↔torrentId
                            // mapping not yet maintained. destinationPath is null until this is added.
                            val torrents = ids.mapNotNull { id ->
                                daemonControl.getTorrentStatus(id)?.let { status ->
                                    TorrentListItem(
                                        id = status.id,
                                        name = status.name,
                                        state = status.state,
                                        progress = status.progress,
                                        downloadRate = status.downloadRate,
                                        uploadRate = status.uploadRate,
                                        peers = status.peers,
                                        savePath = status.savePath,
                                        destinationPath = null // TODO: match via maintained mapping
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
                            if (daemonControl.pauseTorrent(id)) {
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
                            if (daemonControl.resumeTorrent(id)) {
                                call.respond(ControlResponse("ok"))
                            } else {
                                call.respond(HttpStatusCode.NotFound, ErrorResponse("Torrent $id not found"))
                            }
                        }

                        // GET /api/torrents/{id}/destination — returns per-torrent destination status.
                        get("/{id}/destination") {
                            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                                return@get
                            }
                            val status = daemonControl.getTorrentStatus(id)
                            // Use the savePath from native as fallback; per-torrent destination
                            // matching requires a magnetUri↔torrentId mapping not yet maintained.
                            val savePath = status?.savePath ?: ""
                            call.respond(DestinationStatusResponse(
                                path = savePath,
                                valid = true,
                                canonicalPath = savePath.ifEmpty { null },
                                status = "ok"
                            ))
                        }

                        // DELETE /api/torrents/{id}?deleteFiles=true|false — remove a torrent.
                        delete("/{id}") {
                            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                                return@delete
                            }
                            val deleteFiles = call.request.queryParameters["deleteFiles"]?.toBooleanStrictOrNull() ?: false
                            if (daemonControl.removeTorrent(id, deleteFiles)) {
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
                            if (!constantTimeEquals(body.currentPassword, currentStored)) {
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

                    // ---- REST API: daemon health and control ----
                    route("/api/daemon") {
                        // GET /api/daemon/health — returns non-sensitive daemon health status.
                        get("/health") {
                            val health = TorrentDaemon.getHealthStatus(appContext)
                            call.respond(health)
                        }

                        // POST /api/daemon/stop — initiates safe stop of the daemon.
                        post("/stop") {
                            Log.i(TAG, "Stop downloads requested from WebUI")
                            TorrentDaemon.stop(appContext)
                            call.respond(ControlResponse("ok"))
                        }
                    }
                }
            }
        }
    }

    /** Builds a JSON snapshot of all torrents and pending alerts. */
    private fun buildSnapshotJson(): String {
        val ids = daemonControl.getAllTorrentIds()
        val torrents = ids.mapNotNull { id ->
            daemonControl.getTorrentStatus(id)?.let { s ->
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
        val alertJson = daemonControl.popAlerts()
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
        daemonControl: DaemonControl,
        assetReader: (String) -> String? = { null }
    ) {
        this.authManager = authManager
        this.daemonControl = daemonControl
        this.assetReader = assetReader
    }

    /** Resets test configuration to production defaults. Call after each test. */
    internal fun resetToDefaults() {
        this.authManager = object : AuthManager {
            @Volatile private var password: String = "start123"
            override fun getPassword(): String = password
            override fun setPassword(newPassword: String) { password = newPassword }
        }
        this.daemonControl = TorrentSession
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

/**
 * Returns true if the given path is the global legacy save directory.
 *
 * Legacy paths are the old single-directory app-private downloads folder. They remain
 * usable for existing torrents but must not be selectable as destinations for new ones.
 */
internal fun isLegacySavePath(path: String, globalLegacyPath: String? = null): Boolean {
    if (globalLegacyPath == null) return false
    val canonical = try { File(path).canonicalPath } catch (_: Exception) { null }
    val legacyCanonical = try { File(globalLegacyPath).canonicalPath } catch (_: Exception) { null }
    return canonical == legacyCanonical
}

/** Constant-time string comparison to mitigate timing attacks. */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    val aBytes = a.toByteArray(Charsets.UTF_8)
    val bBytes = b.toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(aBytes, bBytes)
}

/** Principal for authenticated WebUI requests. Username is ignored; password is the credential. */
object WebUiPrincipal

// ---------------------------------------------------------------------------
// Request/Response DTOs for REST API
// ---------------------------------------------------------------------------

/** Response for GET /api/storage/permission — returns the state name. */
@Serializable
data class StoragePermissionResponse(val state: String)

/** Request body for POST /api/storage/validate. */
@Serializable
data class ValidateRequest(val path: String = "")

/** Response for POST /api/storage/validate. */
@Serializable
data class ValidateResponse(
    val path: String,
    val canonicalPath: String?,
    val isValid: Boolean,
    val rejectionReason: String?
)

/** Response item for GET /api/storage/volumes. */
@Serializable
data class VolumeResponse(
    val path: String,
    val description: String = "",
    val isRemovable: Boolean = false
)

/** Response for POST /api/storage/destinations/{path}. */
@Serializable
data class AddDestinationResponse(val status: String, val path: String)

/** Response for GET /api/storage/latest-selected. */
@Serializable
data class LatestSelectedResponse(val path: String?)

/** Request body for POST /api/torrents/magnet */
@Serializable
data class MagnetRequest(
    val magnet: String = "",
    val destinationPath: String? = null
)

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
    val savePath: String,
    val destinationPath: String? = null
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

/** Response for GET /api/torrents/{id}/destination. */
@Serializable
data class DestinationStatusResponse(
    val path: String,
    val valid: Boolean,
    val canonicalPath: String?,
    val status: String
)
