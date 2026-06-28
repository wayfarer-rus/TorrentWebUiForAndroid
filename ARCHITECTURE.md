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
    AndroidManifest.xml     # App manifest, INTERNET permission
    jni/torrent_jni.cpp     # JNI bridge implementation
    java/.../
      MainActivity.kt       # Compose activity + UI
      TorrentSession.kt     # Kotlin JNI interface
      TorrentStatus.kt      # DTO models
      TorrentViewModel.kt   # ViewModel with polling
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

## Session Lifecycle

1. `TorrentViewModel.init()` calls `TorrentSession.init(context)`
2. `TorrentSession.init()` loads `libtorrent-jni.so`, creates native session, sets save path
3. ViewModel starts 1-second polling coroutine
4. Each poll cycle: `popAlerts()` → `getAllTorrentIds()` → `getTorrentStatus()` for each
5. On `onCleared()`, ViewModel stops polling and calls `TorrentSession.destroy()`
6. Native `nativeDestroy()` removes session from global map

## Data Flow: Native to Compose UI

1. Native layer: `lt::torrent_status` queried via `handle.status()`
2. JNI: `nativeGetTorrentStatus()` returns `jlongArray[6]` (id, progress\*1000, downloadRate, uploadRate, peers, stateCode)
3. JNI: `nativeGetTorrentName()` returns `jstring`
4. JNI: `nativeGetSavePath()` returns session save path `jstring`
5. JNI: `nativePopAlerts()` drains alert queue each cycle
6. Kotlin: `TorrentSession.getTorrentStatus()` assembles `TorrentStatus` DTO
7. ViewModel: Polls every 1s, updates `MutableStateFlow<TorrentUiState>`
8. Compose: `collectAsStateWithLifecycle()` drives UI updates

## Known Limitations (Stage 1)

- **No session persistence across process death.** Session is lost on app kill.
- **No WebUI.** Android UI only.
- **No SAF.** App-private storage only.
- **No foreground service.** Torrents stop when app is backgrounded.
- **Single session.** No multi-session support.
- **No encryption/HTTPS tracker support.** OpenSSL disabled.
- **Polling-based.** No alert-driven updates (alerts are consumed for queue management only).
- **Magnet-only.** No `.torrent` file support in Stage 1.
