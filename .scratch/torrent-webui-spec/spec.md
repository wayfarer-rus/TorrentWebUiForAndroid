---
Type: spec
Status: ready-for-agent
Labels: wayfinder:spec, assigned-to: claude
---

# Torrent WebUI for Android — Product Spec

## Problem Statement

Mobile torrent clients on Android are either:
- **Heavy desktop ports** (qBittorrent, Deluge) that drain battery and consume excessive resources
- **Feature-poor mobile apps** that lack the control power users need for media server workflows

Users with home torrenting setups (media servers, large downloads) want:
- **LAN-only WebUI** — control torrents from laptop/browser without installing another app
- **VPN-safe operation** — torrent traffic routed through ExpressVPN (app-specific routing), WebUI reachable on local network
- **SSD storage** — download to USB OTG drive, not phone internal storage
- **Background resilience** — torrents continue when screen is off or app is backgrounded

Current solutions don't address this combination. This spec describes an Android app that embeds libtorrent, exposes a Transmission-like WebUI over LAN, and routes torrent traffic through ExpressVPN.

---

## Solution

An Android app that:
1. **Embeds libtorrent-rasterbar** via JNI (no external HTTP API dependency)
2. **Serves a SvelteKit WebUI** from Ktor (single port, relative paths)
3. **Routes torrent P2P traffic** through ExpressVPN (app-specific routing)
4. **Stores downloads on USB OTG SSD** via Storage Access Framework (SAF)
5. **Survives screen-off and backgrounding** via ForegroundService

The app has no native Android UI — the WebUI is the only user-facing surface. This eliminates Android UI development overhead and lets users control torrents from any browser on the LAN.

---

## User Stories

### Core Torrent Management (MVP)

1. As a user, I want to add a torrent via magnet link or URL so that I can start downloading content immediately
2. As a user, I want to see all my torrents in a list with status and progress so that I can monitor downloads at a glance
3. As a user, I want to see real-time download and upload speeds so that I can track bandwidth usage
4. As a user, I want to pause and resume individual torrents so that I can manage bandwidth allocation
5. As a user, I want to remove torrents (with option to delete files) so that I can clean up completed downloads
6. As a user, I want the app to survive screen-off so that I can leave downloads running overnight
7. As a user, I want the WebUI to be reachable from my laptop on LAN so that I can control torrents from my primary device

### Networking and VPN (MVP)

8. As a user with ExpressVPN, I want all torrent traffic to route through the VPN tunnel so that my ISP doesn't see what I'm downloading
9. As a user, I want the WebUI to be accessible from any device on my local network so that I can use my laptop, tablet, or phone interchangeably
10. As a user, I want the app to work correctly even when WebUI responses also flow through VPN so that I don't need complex network configuration

### Storage (Milestone 4)

11. As a user, I want to select a USB OTG SSD as my download destination so that I don't fill up my phone's internal storage
12. As a user, I want the app to persist my storage selection across reboots so that I don't have to reconfigure after restarting
13. As a user, I want the app to pause torrents when I unplug the SSD so that I don't lose download progress
14. As a user, I want the app to auto-resume torrents when I replug the SSD so that downloads continue without manual intervention
15. As a user, I want to know when storage is unavailable so that I can take corrective action

### Reliability (Milestone 3, 9)

16. As a user, I want the torrent engine to run in a foreground service so that Android doesn't kill it when the app is backgrounded
17. As a user, I want a notification showing active download status so that I can see progress from the system tray
18. As a user, I want the app to recover torrents after a reboot so that I don't lose track of active downloads
19. As a user, I want clear error messages when something goes wrong so that I can diagnose and fix issues
20. As a user, I want the app to handle USB disconnects gracefully so that downloads pause cleanly instead of crashing

### Future Features (Post-MVP)

21. As a power user, I want to set per-torrent download/upload speed limits so that I can prioritize other network traffic
22. As a media server user, I want to organize completed downloads into named folders so that my Jellyfin/Plex server can discover them
23. As a user, I want to see which files in a multi-file torrent are downloading so that I can prioritize specific content
24. As a user, I want to manage tracker lists so that I can add/remove announce servers
25. As a user, I want RSS feed support so that I can auto-download content from my favorite trackers
26. As a user, I want to schedule downloads so that I can batch process during off-peak hours
27. As a user, I want push notifications when downloads complete so that I'm alerted even when not looking at the WebUI
28. As a power user, I want per-torrent rules (e.g., "always seed torrents tagged 'important'")

---

## Implementation Decisions

### Torrent Engine: libtorrent-rasterbar via JNI

**Decision:** Use libtorrent 2.0.10 embedded in the APK via NDK/CMake, with a JNI bridge (`torrent_jni.cpp`) exposing Kotlin-facing APIs.

**Rationale:**
- Existing codebase already has a working JNI bridge (Stage 1 proof of concept)
- libtorrent is battle-tested, actively maintained, and supports all required features (magnet links, DHT, trackers, peer exchange)
- No external HTTP API dependency (unlike qBittorrent/Deluge engines) — the app IS the torrent client
- Headers-only Boost dependency is already bundled in `dep/`

**Constraints:**
- All libtorrent API calls serialized via global mutex (`g_mutex`) — acceptable for MVP (infrequent operations)
- Reduce `connections_limit` to 80 for Android file descriptor safety (default 160 exceeds typical Android limits under heavy load)
- Alert processing currently only logs — needs forwarding to Kotlin for MVP (ticket 05)

### Frontend: SvelteKit compiled to static assets

**Decision:** Build SvelteKit app on laptop (outside Android Studio), output static HTML/CSS/JS bundle, copy to Android `assets/www/`, serve via Ktor.

**Rationale:**
- No Node.js tooling in Android Studio — build on laptop, drop output into assets
- SvelteKit produces small bundles (~100-500KB) with zero framework tax in APK
- Relative paths work automatically — Ktor serves both static files and API on same port, browser resolves `/api/*` to same origin
- Svelte stores handle state management (no Redux/Zustand needed)
- Native `fetch()` and `WebSocket` APIs handle HTTP/WebSocket — no extra npm packages

**Build process:**
1. Developer runs `npm run build` in `web/` directory on laptop
2. Output copied to `app/src/main/assets/www/` (or automated via Gradle task)
3. Ktor serves from assets via `StaticContent` plugin

### Backend: Ktor Server on single port

**Decision:** Single Ktor server (Netty engine) binds to `0.0.0.0:<port>`, serving static files, REST API, and WebSockets on the same port.

**Rationale:**
- Simplifies networking — no separate ports for static/API/WS
- Browser relative paths work automatically (from ticket 04)
- Ktor's Netty engine is high-performance and well-tested for Android server workloads

**Routes:**
- `/` → Serves `index.html` + JS/CSS bundle from assets
- `/api/*` → REST handlers (add/remove/pause/resume torrents, list status)
- `/ws/*` → WebSocket handler (real-time progress updates to browser)

**Port:** Default 8080, configurable via app settings (post-MVP). Use high non-standard port to avoid conflicts.

### Networking: ExpressVPN app-specific routing

**Decision:** Accept that ALL traffic from our process (WebUI responses + libtorrent P2P) flows through ExpressVPN tunnel. WebUI "on LAN" means reachability via LAN IP, not that response traffic stays on LAN.

**Rationale:**
- ExpressVPN intercepts at kernel routing level via TUN interface — cannot bypass per-socket (`bindSocket()` doesn't work)
- Separate processes (Option 2 in ADR) adds complexity for negligible benefit (WebUI traffic is small)
- WebUI overhead through VPN is imperceptible (~10-20% for small packets)
- Torrent P2P traffic is the priority — libtorrent creates its own sockets independently routed by ExpressVPN

**User configuration:** User adds our app (`com.andriefimov.torrentwebui`) to ExpressVPN's whitelist. That's it.

### Storage: Android Storage Access Framework (SAF)

**Decision:** Use SAF `ACTION_OPEN_DOCUMENT_TREE` for USB OTG drive access. Persist URI via `takePersistableUriPermission()` to survive reboots.

**Rationale:**
- SAF handles permissions securely (no `MANAGE_EXTERNAL_STORAGE` needed)
- URIs are stable identifiers — survive USB port changes and mount point changes (volume ID unchanged)
- URI breaks only if drive is formatted or replaced with different drive (volume ID changes)

**MVP handling:**
- **Onboarding**: User selects folder via `ACTION_OPEN_DOCUMENT_TREE` → persist URI
- **Startup**: Verify URI is valid (`openFileDescriptor()`), wait up to 30 seconds for storage, error out if unavailable
- **USB disconnect**: Register `BroadcastReceiver` for `ACTION_MEDIA_UNMOUNTED`, pause all torrents, show banner, auto-retry every 10 seconds
- **Auto-resume**: When storage becomes available again, resume all torrents

**Full feature set:** Multiple named destinations, storage health monitoring, automatic fallback to internal storage.

### Architecture: Single ForegroundService owns everything

**Decision:** One `ForegroundService` hosts Ktor server, TorrentSession singleton, and AlertDispatcher coroutine. No microservices, no separate processes.

**Rationale:**
- Matches "low resource footprint" sustainability requirement
- Single serialization point (JNI mutex) is simpler than IPC
- Foreground service survives screen-off and backgrounding (required by Android for long-running tasks)

**Lifecycle:**
1. `MainActivity.onCreate()` → load libtorrent JNI library, start ForegroundService
2. `ForegroundService.onStartCommand()` → init TorrentSession (native libtorrent session), start AlertDispatcher (2s coroutine polling alerts + status), start Ktor server
3. `[POST-MVP]` TorrentSession.restoreState() → load saved session state, libtorrent auto-resumes active torrents
4. WebUI ready — user navigates to `http://<device-lan-ip>:<port>`

**Threading model:**
- **Main (UI)**: MainActivity, ViewModel state collection — Compose UI rendering (note: Compose UI removed in favor of WebUI, but MainActivity still exists as entry point)
- **Service (background)**: ForegroundService, Ktor Server, TorrentSession — HTTP/WebSocket request handling
- **AlertDispatcher coroutine**: Dedicated `Dispatchers.IO` coroutine, 2-second poll of `nativePopAlerts()` + `getAllTorrentStatus()`, categorize alerts, post to EventBus
- **JNI (native)**: `torrent_jni.cpp` — all calls serialized by `g_mutex`
- **libtorrent internal**: Native C++ threads (DHT, tracker, peer connections)
- **Ktor internal**: Ktor's thread pool (coroutine-based)

### Event System: EventBus + AlertDispatcher

**Decision:** AlertDispatcher polls libtorrent alerts every 2 seconds, categorizes them (error, status_change, peer_connection), posts to EventBus singleton. ViewModels observe EventBus for UI updates. Ktor WebSocket handler reads from same event stream for real-time browser pushes.

**Rationale:**
- Decouples engine events from UI (existing pattern in codebase)
- Single data flow — no duplicate status collection for WebUI vs ViewModel
- AlertDispatcher consolidates polling (replaces per-torrent 1-second poll in current code)

**Current gap:** `nativePopAlerts()` only logs alerts — needs to return structured data (alert type + message) for forwarding.

### Threading: Mutex serialization for JNI

**Decision:** All libtorrent API calls go through global mutex `g_mutex`. Only one native call executes at a time, regardless of which Kotlin thread initiated it.

**Rationale:**
- Simple and correct for MVP (torrent operations are infrequent and fast)
- Lock contention is negligible — add/remove/pause/resume take milliseconds, status queries are read-only
- Documented as scalability concern for full feature set (per-torrent mutex or async alert callback would be needed later)

### Authentication: Out of scope for MVP

**Decision:** Local network access is the security boundary. No authentication in MVP.

**Rationale:**
- WebUI only reachable from LAN (Ktor binds `0.0.0.0`, but user controls which devices are on their network)
- Adding auth (password, mTLS) adds complexity for little benefit in trusted LAN environments
- Document as future enhancement (Milestone 2+ if needed)

### Session Persistence: Deferred to full feature set

**Decision:** Skip `save_state()` / `load_state()` for MVP. Document as known limitation (torrents lost on reboot).

**Rationale:**
- MVP is about proving the architecture works (native engine + WebUI + VPN routing)
- Session persistence adds complexity (serialization, migration, storage I/O) that doesn't block core validation
- Full feature set requires it for "resilience across reboots" requirement

---

## Testing Decisions

### What makes a good test

**Test external behavior, not implementation:**
- Verify that adding a magnet link results in a torrent appearing in the list with correct state
- Verify that pausing a torrent changes its state to "paused" and stops download progress
- Verify that removing a torrent (with delete files) cleans up both libtorrent handle and on-disk files
- Verify that WebUI shows real-time progress updates via WebSocket

**Don't test:**
- Internal mutex locking (implementation detail)
- Specific JNI function names (test through Kotlin API)
- libtorrent internal behavior (test through our abstraction layer)

### Modules to test

**Unit tests (Kotlin/JVM):**
- `TorrentSession` — mock JNI calls, verify Kotlin-side error handling and state mapping
- `TorrentViewModel` — verify UI state updates in response to events (use Turbine for Flow testing)
- `AlertDispatcher` — verify alert categorization and EventBus posting

**Integration tests (Ktor test host):**
- REST API endpoints — verify add/remove/pause/resume work via HTTP
- WebSocket handler — verify real-time updates are pushed to connected clients
- Static file serving — verify SvelteKit bundle is served correctly

**Instrumented tests (Android device):**
- End-to-end flow: add magnet → wait for download → verify progress updates in WebUI
- USB disconnect handling: unplug drive mid-download, verify torrents pause and banner shows
- Auto-retry: replug drive, verify torrents resume automatically

**Manual testing (required):**
- Real-device validation of libtorrent functionality (download actual torrents)
- VPN routing verification (confirm torrent traffic flows through ExpressVPN tunnel)
- Storage resilience testing on multiple USB drives and Android versions

### Prior art

**Existing tests in codebase:**
- `ExampleUnitTest.kt` — JUnit 4 example (addition_isCorrect)
- `ExampleInstrumentedTest.kt` — AndroidX test example

**Testing libraries to add:**
- `kotlinx-coroutines-test` — coroutine testing utilities (Dispatchers.setMain)
- `turbine` — Flow testing (assert emissions, test terminal events)
- `mockk` — mocking framework (mock JNI calls, Android APIs)
- `ktor-server-test-host` — Ktor integration testing (TestApplicationEngine, TestCall)

---

## Out of Scope

### MVP (Milestone 1-2)

- **Native Android UI** — WebUI is the only user-facing surface. No Compose/Material Design in MVP (existing MainActivity.kt with Compose UI will be replaced by WebUI).
- **Authentication** — Local network access is the security boundary. No password, mTLS, or user accounts in MVP.
- **Bandwidth limits** — No per-torrent or global speed caps in MVP. libtorrent defaults (connection limit 80) are sufficient.
- **Session persistence** — Torrents lost on reboot. Documented as known limitation, deferred to full feature set.
- **Multiple storage destinations** — Single USB OTG drive supported in MVP. Named folders deferred to Milestone 4+.
- **RSS feeds, schedules, push notifications** — Post-MVP features (Milestone 10).
- **Selective file download** — All files in a torrent download together. Deferred to full feature set.
- **Tracker management UI** — Trackers managed via magnet links or torrent files. No separate tracker list editing in MVP.
- **Categories/tags** — No categorization system in MVP. Deferred to full feature set if demonstrably useful.

### Future considerations (not in any milestone)

- **iOS or desktop port** — Android-only host. No cross-platform goals.
- **VPN provider abstraction** — ExpressVPN is a fixed dependency (app-specific routing). No support for other VPN providers.
- **HTTPS/mTLS** — Local HTTP only in MVP. Future security hardening may add HTTPS, but WebUI traffic going through VPN makes this less critical.
- **Microservices architecture** — Single process, single port. No separate Ktor/VPN/torrent processes.

---

## Further Notes

### Phased rollout

**Milestone 1: Native Engine Proof (current)**
- Bundle libtorrent via NDK/CMake
- Minimal JNI bridge (add/remove/pause/resume/status)
- Download to app-private external storage
- Display: name, state, progress, rates, peers, save location
- Validate with Ubuntu ISO torrent only

**Milestone 2: Browser Control Proof (next)**
- Ktor server with REST API + WebSocket
- SvelteKit WebUI compiled to static assets
- LAN access from laptop browser
- Add magnet, list queue, pause/resume/remove from WebUI

**Milestone 3: Persistent Daemon**
- ForegroundService with notification
- Queue persistence across process death (save/load state)
- Reboot recovery (later)

**Milestone 4: Storage Model**
- SAF onboarding flow in Android app (or WebUI settings)
- One approved destination at first
- Named destination model exposed to WebUI

**Milestone 5: VPN-Safe Appliance Validation**
- Deployment documentation for VPN split tunneling
- LAN WebUI remains reachable while torrent traffic is VPN-routed
- VPN drop validation (torrents pause/resume gracefully)

**Milestone 6: Consumer Onboarding**
- Minimal setup wizard: password (future), storage location, health status
- Safe defaults applied automatically

**Milestone 7: Polished WebUI**
- Responsive desktop/mobile layout
- Queue cards with progress
- Details drawer for torrent info
- Clean empty states

**Milestone 8: Media-Server Workflow**
- Intake and completed folder organization
- Optional logical destinations
- Jellyfin-friendly naming/structure

**Milestone 9: Reliability Hardening**
- USB disconnect/reconnect
- Storage full
- Corrupted resume state
- Permission revocation
- Network transition (WiFi ↔ mobile)
- Restart recovery

**Milestone 10: Optional Extras**
- RSS feeds
- Download schedules
- Push notifications
- Per-torrent rules
- Advanced mode toggle

### Sustainability requirements

- **Background running (screen-off)**: ForegroundService with notification
- **Reboot resilience**: Session persistence (save/load state) — deferred to Milestone 3
- **Low resource footprint**: Single process, no microservices, mutex serialization (acceptable for MVP)

### Dependencies summary

**Android/Kotlin:**
- Ktor 3.0.1 (server-core, netty, serialization-json, content-negotiation, static-content, websockets)
- Kotlinx Serialization 1.7.3
- AndroidX Core, Compose (for MainActivity entry point), ViewModel/Lifecycle
- Testing: JUnit 4, Turbine, MockK, Ktor test host

**Native (NDK/CMake):**
- libtorrent-rasterbar 2.0.10 (bundled in `dep/libtorrent`)
- Boost (headers-only, bundled in `dep/`)

**Frontend (npm):**
- SvelteKit 2.0, Vite 6.0, @sveltejs/adapter-static
- State management: Svelte stores (built-in)
- HTTP/WebSocket: native fetch() and WebSocket APIs (built-in)

### Open questions for future consideration

1. **If we ever need true LAN-only WebUI** (e.g., for regulatory reasons): Option 2 (separate app) would be the path. But this is not an MVP concern.

2. **If ExpressVPN changes its API**: Future versions might support per-socket routing or more granular app-specific policies. Monitor for changes.

3. **If we add HTTPS/mTLS** (post-MVP security hardening): The VPN overhead becomes more relevant because TLS adds its own encryption. But this is a future concern.

4. **If we need per-torrent fine-grained operations** (selective file download, individual peer management): Mutex serialization becomes a bottleneck. Consider per-torrent mutex or async alert callback.

---

## Acceptance Criteria (MVP)

- [ ] App builds and runs on arm64-v8a Android 13+ device
- [ ] Magnet added via WebUI → torrent downloads through ExpressVPN tunnel
- [ ] WebUI reachable from laptop on LAN (`http://<device-lan-ip>:8080`)
- [ ] Pause/resume/delete work end-to-end from WebUI
- [ ] Live progress/speed updates in torrent list (WebSocket)
- [ ] Diagnostics panel shows valid ABI, libtorrent version, and session state
- [ ] USB disconnect pauses torrents, replug resumes them
