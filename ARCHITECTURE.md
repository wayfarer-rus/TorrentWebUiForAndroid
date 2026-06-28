# Architecture Overview

## Layers

```
┌─────────────────────────────────────────────┐
│  WebUI (browser, LAN)                        │
│  Android UI (Compose, onboarding/controls)   │
├─────────────────────────────────────────────┤
│  Shared Domain / Backend Model               │
│  - Torrent state DTOs                        │
│  - Storage destination model                 │
│  - Settings model                            │
├─────────────────────────────────────────────┤
│  Android Service Layer                       │
│  - Foreground service (future)               │
│  - SAF permission management                 │
│  - Lifecycle and recovery                    │
├─────────────────────────────────────────────┤
│  JNI Bridge (narrow, typed DTOs)             │
├─────────────────────────────────────────────┤
│  Native Engine (libtorrent, C++)             │
│  - Session lifecycle                         │
│  - Torrent management                        │
│  - Network I/O                               │
└─────────────────────────────────────────────┘
```

## Key Conventions

### Kotlin / Compose
- Kotlin + Jetpack Compose for all Android UI.
- ViewModels for UI state; no business logic in composables.
- Single-source settings model shared between Android UI and WebUI.

### JNI Boundary
- Kotlin never owns native objects directly.
- Native layer manages session lifecycle (create, destroy, pause, resume).
- JNI exchanges small, typed DTOs. No raw pointers, no giant JSON blobs.
- All native errors surfaced as structured Kotlin exceptions, not crashes.

### Native Engine
- libtorrent-rasterbar is the target engine (pending license review).
- Native build via Android NDK / CMake.
- Target ABI: `arm64-v8a` first.
- Native objects are reference-counted or owned exclusively by the C++ side.

### WebUI
- LAN-only HTTP server embedded in the app.
- Password authentication required by default.
- Consumes the same domain model as Android UI.
- No raw filesystem browsing; uses app-approved named destinations.
- Future: WebSocket for live updates.

### Storage
- Proof-of-concept: app-private external storage.
- Production: SAF (Storage Access Framework) with named, user-approved locations.
- WebUI never sees raw Android paths or document URIs.

### Security
- No cloud dependency; local-only operation.
- No public Internet exposure by default.
- No VPN provider coupling.
- Credentials and sensitive data never logged.
