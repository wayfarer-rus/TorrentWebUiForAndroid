# Architecture Overview

## Layers

```
┌─────────────────────────────────────────────┐
│ Android fallback UI + authenticated WebUI   │
├─────────────────────────────────────────────┤
│ TorrentDaemon (foreground lifecycle owner)  │
│ - Ktor server, queue/journal recovery        │
│ - permission/storage safety transitions      │
├─────────────────────────────────────────────┤
│ Shared domain/backend model                  │
│ - canonical per-torrent destinations         │
│ - durable queue, catalog, and move states    │
├─────────────────────────────────────────────┤
│ JNI Bridge (TorrentSession object)           │
│ - narrow typed add/move/verify operations    │
│ - no raw pointers or giant JSON models       │
├─────────────────────────────────────────────┤
│ Native Engine (libtorrent 2.0.10, C++)       │
│ - native session/handle ownership            │
│ - transfer, move, and piece verification     │
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
      MainActivity.kt       # Minimal permission/service-health fallback UI
      TorrentDaemon.kt      # Foreground owner of native session, Ktor, and recovery
      WebUiServerController.kt # Swappable active/candidate Ktor lifecycle seam
      WebUiPortCoordinator.kt # Atomic bind/persist/promote policy + durable port store
      TorrentServer.kt      # Authenticated WebUI/API using the shared backend model
      TorrentSession.kt     # Sole Kotlin JNI entry point (implements DaemonControl)
      TorrentViewModel.kt   # Android fallback state; does not own the native lifecycle
      DaemonControl.kt      # Shared typed operation/lifecycle seam
      QueueStore.kt         # Durable queue intent with canonical per-torrent destinations
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

## Daemon and Storage Control Seam (M3/M4)

- **`TorrentDaemon`** is the production lifecycle owner for the native session, alert dispatcher, durable recovery, and Ktor server; Activity/ViewModel cleanup never destroys daemon-owned native state.
- **`WebUiServerController`** is daemon-owned and isolates Ktor engine lifecycle from native-session ownership. It can bind one candidate beside the active server, then promote or discard it without restarting or destroying libtorrent; promotion is a non-throwing commit point, and failed old-engine retirement remains controller-owned while the daemon retries cleanup without disturbing the active replacement.
- **`WebUiPortCoordinator`** owns the validated `1024–65535` bind → synchronous persist → promote transaction. Its SharedPreferences adapter defaults to `8080`; bind and persistence failures preserve the previous configured/effective port, while cold-start bind failure keeps the configured value and reports no effective listener to Android.
- **`TorrentServer`** configures authenticated WebUI/API routes and creates Ktor engines for daemon-supplied ports but does not own their lifecycle or port configuration.
- **`DaemonControl`** unifies lifecycle and small typed torrent operations. `TorrentAddRequest` carries a canonical destination and initial pause policy; move/rollback/verification remain narrow JNI calls.
- **`TorrentSession`** is the sole Kotlin JNI entry point while native code owns libtorrent objects.
- **`QueueStore`**, **`DestinationCatalog`**, and **`MoveJournal`** are the durable authority consumed by both Android fallback state and authenticated WebUI/API responses.
- **`DefaultAuthManager`** is the single durable WebUI Password authority shared by Ktor authentication, WebUI password changes, and Android-local recovery. Password writes use synchronous SharedPreferences commit so failure is observable. The onboarding change stores the new Password before `OnboardingCoordinator` records **changed** and completion, then forces a full-page HTTP Basic reauthentication; the normal settings operation still requires current and new values. **`PasswordResetController`** owns only confirmation state and the fixed reset to `start123`; it accepts no password input and has no daemon, server, or native-session lifecycle access.
- **`OnboardingCoordinator`** owns the durable first-M6 marker, one-time established-installation migration, Password Decision, completion prerequisites, and consumer-only Onboarding Readiness projection. Password deferral is persisted before eligible completion; once complete, later readiness/destination loss cannot reopen onboarding. The daemon initializes this state before Ktor binds so later queue, destination, or Password changes cannot be mistaken for pre-existing migration evidence.
- **`RecommendedDestinationService`** derives the canonical `Download/Torrents` proposal from Android's primary Storage Volume without creating it. Confirmation alone performs recursive creation, post-creation canonical confinement/writability validation, and the catalog's atomic approval/Latest Selected update; failure rolls back only directories created by that operation and still provably empty.
- **`AndroidStorageApiOperations`** is the shared authenticated Directory Browser authority for normal controls and Consumer Onboarding. It exposes only mounted Android-reported roots, rejects unavailable or out-of-volume parents before browsing, revalidates every pasted or browsed path at approval time, and delegates the single Approved Destination/Latest Selected write to `DestinationCatalog`. Browser selection state is intentionally ephemeral.
- Recovery validates queue/journal records and storage availability before native work. Corrupt journals fail closed; unavailable destinations enter native recovery paused from the first instant.

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

## Authenticated WebUI/API (M3/M4)

- Authenticated **`GET /api/onboarding/status`** returns only durable completion, Password Decision, Approved Destination presence, and consumer-level Onboarding Readiness. Authenticated onboarding routes expose the canonical Recommended Destination proposal/confirmation and **Set it later** deferral. Before completion, normal torrent REST reads/mutations are rejected with `409 onboarding_incomplete`, authenticated WebSockets close with a policy violation, and storage/Password/Android recovery operations remain available.
- **`GET /api/daemon/health`** returns non-sensitive daemon lifecycle/recovery state; **`POST /api/daemon/stop`** performs shared safe stop.
- Authenticated storage routes expose permission state, validated volume roots/canonical directories, approved destinations, latest selection, per-torrent destination availability, and move status/retry/cancel.
- Torrent REST and WebSocket snapshots resolve canonical destinations from the durable queue, not transient native paths.
- Permission revocation replaces native operation control with a permission-blocked backend while keeping authenticated Ktor available. An explicit stop still removes Ktor and requires Android fallback restart.

## Android Fallback UI (M3/M6)

- **`MainActivity`** remains a deliberately minimal fallback: daemon health, Android permission recovery, configured/effective WebUI Port, and Start/Stop downloads controls.
- Port changes are sent to the existing foreground daemon by intent; the ViewModel only observes Android-local status and never initializes, pauses, or destroys the native session.
- Android does not discover or display a LAN address. No queue list, magnet input, destination selection, or per-torrent controls are present (those are WebUI-only).
- Normal password changes remain an authenticated WebUI control (`POST /api/settings/password`). Android exposes only a confirmed local recovery action that restores `start123`; the next HTTP Basic check reads the shared manager, rejects previous credentials, and prompts browser reauthentication.

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
2. JNI: `nativeGetTorrentStatus()` returns `jlongArray[7]` (id, progress\*1000, downloadRate, uploadRate, peers, stateCode, paused flag)
3. JNI: `nativeGetTorrentName()` returns `jstring`
4. JNI: `nativeGetSavePath()` returns session save path `jstring`
5. JNI: `nativePopAlerts()` drains alert queue each cycle
6. Kotlin: `TorrentSession.getTorrentStatus()` assembles `TorrentStatus` DTO
7. ViewModel: Polls every 1s, updates `MutableStateFlow<TorrentUiState>`
8. Compose: `collectAsStateWithLifecycle()` drives UI updates

## Milestone 4 Storage and Recovery

- New torrents use backend-validated canonical shared/external filesystem paths; SAF URIs and synthetic aliases are rejected.
- The versioned queue persists per-torrent destination, user pause intent, and storage-safety pause state. The destination catalog and move journal are app-private durable configuration.
- One-torrent moves pause only the selected torrent, retain source/target recovery state, use libtorrent movement and piece verification, and update the durable queue only after verification. Retry/cancel remain explicit.
- All Files Access loss keeps an authenticated permission-blocked WebUI available while native storage work is stopped. Restoration never auto-resumes storage-safety-paused torrents.
- Automated M4 acceptance uses a real API 36 AVD, JNI/libtorrent, Ktor, authenticated browser traffic over owned ADB forwarding, deterministic fixtures, and teardown verification. Physical-device deployment validation remains optional and unperformed.

## Milestone 6 Consumer Onboarding Acceptance

- `m6-clean-install-runner.mjs` owns the bounded clean-install happy path across the visible Android permission UI, daemon/JNI startup, default-port Ktor listener, packaged WebUI, Chromium HTTP Basic authentication, Recommended Destination creation, and Password deferral.
- The runner refuses physical devices and non-isolated AVDs, forbids shell permission grants, starts timing at the first actionable Android bootstrap screen, compares the backend path with ADB canonical filesystem evidence, and fail-closes every teardown audit.
- Completion is rechecked after browser refresh and an explicit daemon stop/restart through reopened MainActivity. Teardown stops the daemon/server and removes the created folder, application state, recovery records, credential state, virtual storage, and ADB forwarding.

## Known Limitations

- **No encryption/HTTPS tracker support.** OpenSSL disabled.
- **Magnet-only.** No `.torrent` file upload support.
- **No physical-device M4 deployment check yet.** The required automated acceptance is emulator-based; any later Termux/SSH comparison must be recorded separately in `TEST_REPORT.md`.
