# Architecture Overview

## Layers

```
┌─────────────────────────────────────────────┐
│  Android UI (Compose, onboarding/controls)   │
├─────────────────────────────────────────────┤
│  Shared Domain / Backend Model               │
│  - TorrentStatus DTO                         │
│  - NativeDiagnostics DTO                     │
├─────────────────────────────────────────────┤
│  ViewModel (TorrentViewModel)                │
│  - Session lifecycle management              │
│  - 1-second polling of native state          │
├─────────────────────────────────────────────┤
│  JNI Bridge (TorrentSession object)          │
│  - Narrow, typed DTOs                       │
│  - No raw pointers, no JSON                 │
├─────────────────────────────────────────────┤
│  Native Engine (libtorrent 2.0.10, C++)      │
│  - Session lifecycle                        │
│  - Torrent management                       │
│  - Network I/O                              │
└─────────────────────────────────────────────┘
```

## Module Layout

```
app/
  build.gradle.kts          # Android app module config
  CMakeLists.txt            # Native build (libtorrent + JNI)
  src/main/
    AndroidManifest.xml     # App manifest, INTERNET/FOREGROUND_SERVICE/POST_NOTIFICATIONS permissions
    jni/torrent_jni.cpp     # JNI bridge implementation (includes resume data methods)
    java/.../
      MainActivity.kt       # Compose activity (M3: minimal fallback — health + Start/Stop only)
      TorrentDaemon.kt      # Foreground service (M3: owns session + WebUI lifecycle)
      TorrentServer.kt      # Ktor server (M3: adds /api/daemon/health and /api/daemon/stop)
      TorrentSession.kt     # Kotlin JNI interface (implements DaemonControl)
      TorrentViewModel.kt   # ViewModel (routes through DaemonControl seam)
      DaemonControl.kt      # Unified control seam (ops + lifecycle) — M3 addition
      QueueStore.kt         # Durable queue persistence interface + implementations — M3 addition
      RecoverySuppressionStore.kt # Durable force-stop auto-recovery suppression — M3 addition
      ForceStopDetector.kt   # Android API 30+ force-stop exit-reason reader — M3 addition
      TorrentStatus.kt      # DTO models
libtorrent/                 # Git submodule (v2.0.10)
dep/                        # Boost 1.86.0 headers + checksum file
scripts/
  bootstrap-deps.sh         # Download and verify Boost
```

## JNI Ownership Model

- **Native layer owns all libtorrent objects.** Kotlin never directly owns or destroys native handles.
- `TorrentSession` Kotlin object is the sole JNI entry point.
- Native session is created via `nativeInit()` and destroyed via `nativeDestroy()`.
- Torrent handles are stored in a `std::unordered_map<uint64_t, lt::torrent_handle>` within each session entry.
- All JNI entry points are wrapped in `try/catch` to prevent native exceptions from crossing the boundary.
- JNI functions use `extern "C"` to avoid name mangling issues.
- Alert queue is drained each polling cycle via `nativePopAlerts()` to prevent saturation.

## Native Build Approach

- **CMake 3.22.1** + **NDK 29.0.14206865**
- **libtorrent-rasterbar v2.0.10** (commit 74bc93a37) via git submodule
- **Boost 1.86.0** headers downloaded from archives.boost.io, SHA-256 verified
- Target ABI: **arm64-v8a** only
- Build flags:
  - `lt_USE_OPENSSL=OFF`
  - `lt_USE_LIBRESOLVE=OFF`
  - `lt_USE_STREAMING=OFF`
  - `lt_ENABLE_EXAMPLES=OFF`
  - `lt_ENABLE_TESTS=OFF`
  - `lt_ENABLE_PYTHON_BINDINGS=OFF`
  - `lt_STRICT_ANDROID=ON`
  - `-std=c++17`

## Dependency Reproducibility

- **libtorrent**: Pinned git submodule at tag `v2.0.10` (commit `74bc93a37`). Submodule deps (`try_signal`, `asio-gnutls`) are also pinned.
- **Boost 1.86.0**: Downloaded via `scripts/bootstrap-deps.sh` with SHA-256 verification. Checksum pinned in `dep/boost-sha256.txt` and in the bootstrap script. Not tracked in git (headers-only, ~200MB).

## Daemon Control Seam (M3)

- **`DaemonControl`** interface unifies session operations (`TorrentSessionOps`) with lifecycle management (`init`, `destroy`, `getDiagnostics`, resume data methods).
- **`TorrentSession`** object implements `DaemonControl` directly.
- **`DaemonControlFactory`** provides production (`TorrentSession`) and test (mock-backed) entry points.
- **`TorrentServer`** and **`TorrentViewModel`** both talk through `DaemonControl`, so the native session can move behind a foreground service without changing callers.

## Queue Persistence (M3)

- **`QueueStore`** interface provides durable queue intent and per-torrent resume data storage.
- **`FileQueueStore`** implementation uses atomic file replacement (write to temp, rename) for durability.
- **`InMemoryQueueStore`** implementation for unit tests.
- Queue mutations are made durable before reporting success to the initiating control surface.
- Native resume data is checkpointed with atomic replacement no less frequently than once every 30 seconds.
- Recovery records live only in app-private storage and are never exposed through WebUI responses, notifications, or logs.

## Foreground Daemon (M3)

- **`TorrentDaemon`** is a foreground `Service` that owns both the native torrent session and Ktor WebUI server.
- Lifecycle states: `Stopped`, `Starting`, `Running`, `Stopping`, `RecoveryBlocked`.
- Android 13+ requires `POST_NOTIFICATIONS` permission before starting as a foreground service.
- Persistent notification exposes only aggregate state and has one action: **Stop downloads**.
- Safe stop persists queue intent within a 5-second deadline; on failure, daemon remains running with a recoverable error.
- On ordinary system termination, the next app launch restores eligible queue entries (at most 30 seconds of transfer progress may be lost).
- Android Force stop is detected on API 30+ from the latest `REASON_USER_REQUESTED` process exit and persisted as a local suppression marker. App-launch recovery is skipped while queue records remain available; **Start downloads** clears the marker and resumes recovery.

## WebUI Endpoints (M3)

- **`GET /api/daemon/health`** — returns non-sensitive daemon health status (lifecycle state, recovery blocked flag).
- **`POST /api/daemon/stop`** — initiates safe stop of the daemon (invokes shared safe-stop behavior).
- After Stop downloads, the WebUI is unavailable (Ktor server stops) and Android fallback is the required restart path.

## Android Fallback UI (M3)

- **`MainActivity`** is now a deliberately minimal fallback: daemon health display + Start/Stop downloads buttons only.
- No queue list, magnet input, or per-torrent controls (those are WebUI-only).
- Password changes remain a WebUI control surface (`POST /api/settings/password`).

## JNI Resume Data Methods (M3)

- **`nativeSaveTorrentResumeData(sessionId, torrentId)`** — calls `torrent_handle::save_resume_data()` (asynchronous).
- **`nativeLoadTorrentResumeData(sessionId, torrentId)`** — returns serialized `add_torrent_params` as `jbyteArray`.
- **`nativeRemoveTorrentResumeData(sessionId, torrentId)`** — clears resume data for a torrent.

## Session Lifecycle (M3)

1. `TorrentDaemon.start()` checks notification permission, then evaluates the force-stop suppression marker before creating `DaemonControl` (wraps `TorrentSession`).
2. `TorrentDaemon.resume()` is the explicit **Start downloads** action; it clears suppression before recovery. Eligible starts recover the queue from `FileQueueStore` (in a background coroutine)
3. Starts Ktor WebUI server
4. Starts foreground service with notification
5. Starts 30-second checkpoint timer (calls `saveTorrentResumeData()` for each torrent)
6. On user **Stop downloads**: cancels timer, saves queue intent with 5s deadline, stops WebUI, destroys session
7. On ordinary termination: next launch recovers queue and resumes healthy entries

## Data Flow: Native to Compose UI

1. Native layer: `lt::torrent_status` queried via `handle.status()`
2. JNI: `nativeGetTorrentStatus()` returns `jlongArray[6]` (id, progress\*1000, downloadRate, uploadRate, peers, stateCode)
3. JNI: `nativeGetTorrentName()` returns `jstring`
4. JNI: `nativeGetSavePath()` returns session save path `jstring`
5. JNI: `nativePopAlerts()` drains alert queue each cycle
6. Kotlin: `TorrentSession.getTorrentStatus()` assembles `TorrentStatus` DTO
7. ViewModel: Polls every 1s, updates `MutableStateFlow<TorrentUiState>`
8. Compose: `collectAsStateWithLifecycle()` drives UI updates

## Known Limitations

- **No SAF.** App-private storage only (Milestone 4 adds user-selectable destinations via SAF).
- **No encryption/HTTPS tracker support.** OpenSSL disabled.
- **Polling-based.** No alert-driven updates (alerts are consumed for queue management only).
- **Magnet-only.** No `.torrent` file support.
- **No physical-device LAN acceptance yet.** Emulator acceptance tests compile and are ready for AVD execution.
