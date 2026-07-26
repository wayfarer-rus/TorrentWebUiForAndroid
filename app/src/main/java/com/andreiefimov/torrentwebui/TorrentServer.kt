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
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.andreiefimov.torrentwebui.events.AlertEvent
import com.andreiefimov.torrentwebui.events.EventBus
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
     * Move journal — tracks active and interrupted move operations.
     */
    internal var moveJournal: MoveJournal? = null

    /**
     * Move service — executes safe torrent data moves between destinations.
     */
    internal var moveService: MoveService? = null

    /** Stable queue/runtime correlation and serialized durable mutation seam. */
    internal var queueBindings: QueueRuntimeBindings? = null
    internal var durableOperations: DurableTorrentOperations? = null

    /** Prepares shared WebUI dependencies before the daemon starts a server engine. */
    internal fun prepare(context: Context, authManager: AuthManager? = null) {
        appContext = context.applicationContext
        this.authManager = authManager ?: DefaultAuthManager(appContext)
    }

    /** Creates one Ktor engine; lifecycle ownership remains in [WebUiServerController]. */
    internal fun createEngine(port: Int): WebUiServerEngine {
        // Use a lambda instead of top-level module function — Ktor's module function
        // discovery uses reflection and fails on Android (dex transformation).
        val engine = embeddedServer(Netty, port, "0.0.0.0", listOf()) {
            configureApplication(this)
        }
        return object : WebUiServerEngine {
            override fun start() {
                Log.i(TAG, "Starting Ktor server on http://0.0.0.0:$port")
                engine.start(wait = false)
                Log.i(TAG, "Ktor server started successfully")
            }

            override fun stop() {
                engine.stop()
                Log.i(TAG, "Ktor server stopped")
            }
        }
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

        // Basic Authentication — protects every WebUI/API/WebSocket route except /health.
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

            // ---- Authenticated routes ----
            authenticate("Torrent WebUI") {
                // WebSocket: live torrent progress plus alerts fanned out by AlertDispatcher.
                // AlertDispatcher remains the sole native-alert consumer; every frame is one JSON value.
                webSocket("/ws/progress") {
                    Log.i(TAG, "Authenticated WebSocket client connected")

                    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    try {
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
                        scope.launch {
                            EventBus.observeAlerts().collect { alert ->
                                send(Frame.Text(buildAlertJson(alert)))
                            }
                        }

                        for (frame in incoming) {
                            if (frame is Frame.Text && frame.readText() == "ping") {
                                send(Frame.Text("pong"))
                            }
                        }
                    } finally {
                        scope.cancel()
                        Log.i(TAG, "Authenticated WebSocket client disconnected")
                    }
                }
            }

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

                            val removed = catalog.removeDestination(destPath, queueStore, TorrentServer.moveJournal)
                            if (removed) {
                                call.respond(ControlResponse("ok"))
                            } else {
                                val queue = queueStore.loadQueueIntent()
                                val journal = TorrentServer.moveJournal
                                val moveReferencesPath = journal != null &&
                                    (journal.getActiveMoves() + journal.getInterruptedMoves()).any {
                                        it.sourcePath == destPath || it.targetPath == destPath
                                    }
                                if (queue.any { it.destinationPath == destPath } || moveReferencesPath) {
                                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Destination is still referenced by a queue entry or move"))
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
                            TorrentServer.daemonControl.refreshStoragePermissionState(appContext)
                            val state = TorrentServer.daemonControl.storagePermissionState
                            call.respond(StoragePermissionResponse(state.name))
                        }

                        // GET /api/moves — lists all interrupted moves requiring user action.
                        get("/moves") {
                            val journal = TorrentServer.moveJournal
                                ?: run { call.respond(emptyList<InterruptedMoveResponse>()); return@get }
                            val bindings = TorrentServer.queueBindings
                                ?: run { call.respond(emptyList<InterruptedMoveResponse>()); return@get }
                            val interrupted = journal.getInterruptedMoves()
                            call.respond(interrupted.mapNotNull { move ->
                                bindings.runtimeIdFor(move.queueId)?.let { runtimeId ->
                                    InterruptedMoveResponse(
                                        torrentId = runtimeId,
                                        sourcePath = move.sourcePath,
                                        targetPath = move.targetPath,
                                        phase = move.phase.apiName,
                                        createdAt = move.createdAt
                                    )
                                }
                            })
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

                            // Re-check at operation time to catch runtime permission revocation.
                            TorrentServer.daemonControl.refreshStoragePermissionState(appContext)
                            if (!TorrentServer.daemonControl.isStorageReady) {
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    ErrorResponse("Storage permission required. Grant All Files Access in system settings and restart downloads.")
                                )
                                return@post
                            }

                            val catalog = TorrentServer.destinationCatalog ?: run {
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    ErrorResponse("Destination catalog is unavailable")
                                )
                                return@post
                            }
                            val requestedPath = body.destinationPath?.trim()?.takeIf { it.isNotEmpty() }
                                ?: catalog.getLatestSelected()
                                ?: run {
                                    call.respond(
                                        HttpStatusCode.BadRequest,
                                        ErrorResponse("A destination path is required")
                                    )
                                    return@post
                                }

                            // Explicit and latest-selected destinations share the same operation-time validation.
                            if (isLegacySavePath(requestedPath, TorrentDaemon.getLegacySaveDirectory(appContext)?.absolutePath)) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse("Cannot use legacy save directory as destination for new torrents")
                                )
                                return@post
                            }
                            val validation = DirectoryValidationService.validate(appContext, requestedPath)
                            if (!validation.isValid) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse(validation.rejectionReason ?: "Invalid destination path")
                                )
                                return@post
                            }
                            val destinationPath = validation.canonicalPath!!
                            if (!catalog.addDestination(destinationPath)) {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    ErrorResponse("Failed to persist destination selection")
                                )
                                return@post
                            }

                            val operations = TorrentServer.durableOperations ?: run {
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    ErrorResponse("Queue persistence is unavailable")
                                )
                                return@post
                            }
                            val added = try {
                                operations.add(
                                    magnetUri,
                                    TorrentDestination(destinationPath!!),
                                    startPaused = body.startPaused
                                )
                            } catch (e: Exception) {
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    ErrorResponse("Unable to add and persist the torrent")
                                )
                                return@post
                            }

                            call.respond(
                                MagnetResponse(
                                    id = added.runtimeId,
                                    queueId = added.queueId.value,
                                    status = added.status
                                )
                            )
                        }

                        // GET /api/torrents — list all torrents with current state.
                        get {
                            val ids = daemonControl.getAllTorrentIds()
                            val journal = TorrentServer.moveJournal
                            val bindings = TorrentServer.queueBindings
                            val queueStore = TorrentServer.queueStore
                            val queueEntries = queueStore?.loadQueueIntent() ?: emptyList()
                            val torrents = ids.mapNotNull { id ->
                                daemonControl.getTorrentStatus(id)?.let { status ->
                                    val queueId = bindings?.queueIdFor(status.id)
                                    val moveEntry = queueId?.let { qId -> journal?.getMove(qId) }
                                    val moveState = moveEntry?.phase?.apiName
                                    val queueEntry = queueId?.let { qId -> queueEntries.find { it.queueId == qId } }
                                    val destinationPath = queueEntry?.destinationPath?.ifEmpty { null }
                                        ?: status.savePath.ifEmpty { null }
                                    val destinationStatus = when {
                                        queueEntry?.addCollisionState != null -> "storage_conflict"
                                        destinationPath != null &&
                                            TorrentServer.durableOperations?.ensureDestinationAvailable(id, appContext) == false ->
                                            "destination_unavailable"
                                        else -> status.destinationStatus
                                    }
                                    TorrentListItem(
                                        id = status.id,
                                        queueId = queueId?.value,
                                        name = status.name,
                                        state = status.state,
                                        progress = status.progress,
                                        downloadRate = status.downloadRate,
                                        uploadRate = status.uploadRate,
                                        peers = status.peers,
                                        savePath = status.savePath,
                                        destinationPath = destinationPath,
                                        destinationStatus = destinationStatus,
                                        moveState = moveState
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
                            val queueId = try {
                                TorrentServer.durableOperations?.pause(id)
                                    ?: throw IllegalStateException("Queue persistence unavailable")
                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.Conflict, ErrorResponse("Unable to durably pause torrent"))
                                return@put
                            }
                            call.respond(ControlResponse("ok", queueId.value))
                        }

                        // PUT /api/torrents/{id}/resume — resume a paused torrent.
                        put("/{id}/resume") {
                            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                                Log.i(TAG, "Resume request for invalid ID: ${call.parameters["id"]}")
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                                return@put
                            }
                            val queueId = try {
                                TorrentServer.durableOperations?.resume(id, appContext)
                                    ?: throw IllegalStateException("Queue persistence unavailable")
                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.Conflict, ErrorResponse("Unable to resume while destination is unavailable"))
                                return@put
                            }
                            call.respond(ControlResponse("ok", queueId.value))
                        }

                        // GET /api/torrents/{id}/destination — returns per-torrent destination status.
                        get("/{id}/destination") {
                            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                                return@get
                            }
                            val status = daemonControl.getTorrentStatus(id)
                            val queueId = TorrentServer.queueBindings?.queueIdFor(id)
                            val queueEntry = queueId?.let { resolvedQueueId ->
                                TorrentServer.queueStore?.loadQueueIntent()
                                    ?.find { it.queueId == resolvedQueueId }
                            }
                            val durablePath = queueEntry?.destinationPath
                            // Only legacy entries fall back to the native save path.
                            val destinationPath = durablePath ?: status?.savePath.orEmpty()
                            val available = destinationPath.isNotEmpty() &&
                                TorrentServer.durableOperations?.ensureDestinationAvailable(id, appContext) != false &&
                                DirectoryValidationService.validate(appContext, destinationPath).isValid
                            call.respond(DestinationStatusResponse(
                                path = destinationPath,
                                valid = available,
                                canonicalPath = destinationPath.ifEmpty { null },
                                status = when {
                                    destinationPath.isEmpty() -> "not_found"
                                    queueEntry?.addCollisionState != null -> "storage_conflict"
                                    available -> "ok"
                                    else -> "destination_unavailable"
                                }
                            ))
                        }

                        // POST /api/torrents/{id}/move — initiate async move to new destination.
                        post("/{id}/move") {
                            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                                return@post
                            }

                            // Check storage permission.
                            if (!daemonControl.isStorageReady) {
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    ErrorResponse("Storage permission required")
                                )
                                return@post
                            }

                            val moveService = TorrentServer.moveService
                                ?: run { call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Move service unavailable")); return@post }

                            val body = try {
                                call.receive<MoveRequest>()
                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                                return@post
                            }

                            val result = moveService.startMove(id, body.destinationPath)
                            when (result.status) {
                                "ok" -> call.respond(MoveResponse(status = "ok", phase = "moving"))
                                "interrupted" -> call.respond(
                                    HttpStatusCode.Conflict,
                                    MoveResponse(status = "interrupted", phase = MovePhase.Interrupted.apiName, error = result.recoverableError)
                                )
                                "storage_conflict" -> call.respond(
                                    HttpStatusCode.Conflict,
                                    MoveResponse(status = "storage_conflict", phase = MovePhase.StorageConflict.apiName, error = result.recoverableError)
                                )
                                else -> call.respond(
                                    HttpStatusCode.BadRequest,
                                    MoveResponse(status = "error", phase = "error", error = result.recoverableError)
                                )
                            }
                        }

                        // GET /api/torrents/{id}/move/status — returns current move state.
                        get("/{id}/move/status") {
                            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                                return@get
                            }
                            val journal = TorrentServer.moveJournal ?: run {
                                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Move journal unavailable"))
                                return@get
                            }
                            val bindings = TorrentServer.queueBindings ?: run {
                                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Queue bindings unavailable"))
                                return@get
                            }
                            val queueId = bindings.queueIdFor(id) ?: run {
                                call.respond(HttpStatusCode.NotFound, ErrorResponse("Torrent not found"))
                                return@get
                            }
                            val entry = journal.getMove(queueId)
                            if (entry != null) {
                                call.respond(MoveStatusResponse(
                                    phase = entry.phase.apiName,
                                    sourcePath = entry.sourcePath,
                                    targetPath = entry.targetPath
                                ))
                            } else {
                                call.respond(MoveStatusResponse(
                                    phase = "none",
                                    sourcePath = null,
                                    targetPath = null
                                ))
                            }
                        }

                        // POST /api/torrents/{id}/move/cancel — cancel only from move_interrupted.
                        post("/{id}/move/cancel") {
                            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                                return@post
                            }
                            val journal = TorrentServer.moveJournal ?: run {
                                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Move journal unavailable"))
                                return@post
                            }
                            val bindings = TorrentServer.queueBindings ?: run {
                                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Queue bindings unavailable"))
                                return@post
                            }
                            val queueId = bindings.queueIdFor(id) ?: run {
                                call.respond(HttpStatusCode.NotFound, ErrorResponse("Torrent not found"))
                                return@post
                            }
                            val entry = journal.getMove(queueId)
                            if (entry == null || !entry.phase.requiresUserAction) {
                                call.respond(
                                    HttpStatusCode.Conflict,
                                    ErrorResponse("Cancel available only when move requires recovery")
                                )
                                return@post
                            }
                            val moveService = TorrentServer.moveService ?: run {
                                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Move service unavailable"))
                                return@post
                            }
                            if (moveService.cancelMove(id)) {
                                call.respond(ControlResponse("ok"))
                            } else {
                                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Unable to cancel move"))
                            }
                        }

                        // POST /api/torrents/{id}/move/retry — retry from move_interrupted.
                        post("/{id}/move/retry") {
                            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                                return@post
                            }

                            if (!daemonControl.isStorageReady) {
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    ErrorResponse("Storage permission required")
                                )
                                return@post
                            }

                            val body = try {
                                call.receive<MoveRequest>()
                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                                return@post
                            }

                            val journal = TorrentServer.moveJournal
                                ?: run { call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Move journal unavailable")); return@post }
                            val bindings = TorrentServer.queueBindings
                                ?: run { call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Queue bindings unavailable")); return@post }
                            val queueId = bindings.queueIdFor(id)
                                ?: run { call.respond(HttpStatusCode.NotFound, ErrorResponse("Torrent not found")); return@post }
                            val interruptedMove = journal.getMove(queueId)
                            if (interruptedMove?.phase?.requiresUserAction != true || body.destinationPath != interruptedMove.targetPath) {
                                call.respond(HttpStatusCode.Conflict, ErrorResponse("Retry must use the pending move destination"))
                                return@post
                            }
                            val moveService = TorrentServer.moveService
                                ?: run { call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Move service unavailable")); return@post }

                            val result = moveService.retryMove(id, body.destinationPath)
                            when (result.status) {
                                "ok" -> call.respond(MoveResponse(status = "ok", phase = "moving"))
                                "storage_conflict" -> call.respond(
                                    HttpStatusCode.Conflict,
                                    MoveResponse(status = result.status, phase = MovePhase.StorageConflict.apiName, error = result.recoverableError)
                                )
                                else -> call.respond(
                                    HttpStatusCode.Conflict,
                                    MoveResponse(status = result.status, phase = MovePhase.Interrupted.apiName, error = result.recoverableError)
                                )
                            }
                        }

                        // DELETE /api/torrents/{id}?deleteFiles=true|false — remove a torrent.
                        delete("/{id}") {
                            val id = call.parameters["id"]?.toLongOrNull() ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid torrent ID"))
                                return@delete
                            }
                            val deleteFiles = call.request.queryParameters["deleteFiles"]?.toBooleanStrictOrNull() ?: false
                            val queueId = try {
                                TorrentServer.durableOperations?.delete(id, deleteFiles)
                                    ?: throw IllegalStateException("Queue persistence unavailable")
                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.Conflict, ErrorResponse("Unable to durably remove torrent"))
                                return@delete
                            }
                            TorrentServer.moveJournal?.getRetainedMove(queueId)?.let {
                                TorrentServer.moveJournal?.removeMove(queueId)
                            }
                            call.respond(ControlResponse("ok", queueId.value))
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

    /** Builds one valid JSON snapshot using the durable queue as destination authority. */
    internal suspend fun buildSnapshotJson(): String {
        val ids = daemonControl.getAllTorrentIds()
        val journal = TorrentServer.moveJournal
        val bindings = TorrentServer.queueBindings
        val queueEntries = TorrentServer.queueStore?.loadQueueIntent() ?: emptyList()
        val torrents = ids.mapNotNull { id ->
            daemonControl.getTorrentStatus(id)?.let { s ->
                val qId = bindings?.queueIdFor(s.id)
                val moveEntry = qId?.let { journal?.getMove(it) }
                val queueEntry = qId?.let { queueId -> queueEntries.find { it.queueId == queueId } }
                val destinationPath = queueEntry?.destinationPath?.ifEmpty { null }
                    ?: s.savePath.ifEmpty { null }
                val destinationStatus = when {
                    queueEntry?.addCollisionState != null -> "storage_conflict"
                    ::appContext.isInitialized && destinationPath != null &&
                        TorrentServer.durableOperations?.ensureDestinationAvailable(id, appContext) == false ->
                        "destination_unavailable"
                    else -> s.destinationStatus
                }
                TorrentListItem(
                    id = s.id, name = s.name, state = s.state, progress = s.progress,
                    downloadRate = s.downloadRate, uploadRate = s.uploadRate,
                    peers = s.peers, savePath = s.savePath,
                    destinationPath = destinationPath,
                    queueId = qId?.value,
                    destinationStatus = destinationStatus,
                    moveState = moveEntry?.phase?.webSocketApiName()
                )
            }
        }

        // Build the message envelope: {"type":"torrents","data":[...]}
        val torrentsJson = buildString {
            append("[")
            torrents.forEachIndexed { index, item ->
                if (index > 0) append(",")
                append("{\"id\":${item.id}")
                append(",\"name\":\"${escapeJson(item.name)}\"")
                append(",\"state\":\"${escapeJson(item.state)}\"")
                append(",\"progress\":${item.progress}")
                append(",\"downloadRate\":${item.downloadRate}")
                append(",\"uploadRate\":${item.uploadRate}")
                append(",\"peers\":${item.peers}")
                append(",\"savePath\":\"${escapeJson(item.savePath)}\"")
                append(",\"destinationPath\":\"${escapeJson(item.destinationPath ?: "")}\"")
                append(",\"queueId\":\"${escapeJson(item.queueId ?: "")}\"")
                append(",\"destinationStatus\":\"${escapeJson(item.destinationStatus ?: "")}\"")
                append(",\"moveState\":\"${escapeJson(item.moveState ?: "")}\"")
                append("}")
            }
            append("]")
        }
        return """{"type":"torrents","data":$torrentsJson}"""
    }

    /** Builds one WebSocket-safe alert envelope from AlertDispatcher's fan-out channel. */
    private fun buildAlertJson(alert: AlertEvent): String =
        """{"type":"alert","alertType":"${escapeJson(alert.type)}","message":"${escapeJson(alert.message)}"}"""

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
    val destinationPath: String? = null,
    val startPaused: Boolean = false
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
    val destinationPath: String? = null,
    val queueId: String? = null,
    val destinationStatus: String? = null,
    val moveState: String? = null
)

/** Generic success response with status field. */
@Serializable
data class ControlResponse(val status: String, val queueId: String? = null)

/** Magnet add response with torrent ID. */
@Serializable
data class MagnetResponse(val id: Long, val queueId: String? = null, val status: String)

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

/** Request body for POST /api/torrents/{id}/move and retry. */
@Serializable
data class MoveRequest(val destinationPath: String = "")

/** Response for move operations. */
@Serializable
data class MoveResponse(
    val status: String, // "ok", "interrupted", "error"
    val phase: String,
    val error: String? = null
)

/** Response for GET /api/torrents/{id}/move/status. */
@Serializable
data class MoveStatusResponse(
    val phase: String, // "none", "moving", "interrupted"
    val sourcePath: String?,
    val targetPath: String?
)

/** Response item for GET /api/moves — lists interrupted moves requiring user action. */
@Serializable
data class InterruptedMoveResponse(
    val torrentId: Long,
    val sourcePath: String,
    val targetPath: String,
    val phase: String,
    val createdAt: Long
)
