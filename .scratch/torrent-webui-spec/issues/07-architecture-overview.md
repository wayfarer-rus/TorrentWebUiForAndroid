---
Type: prototype
Status: resolved
Labels: wayfinder:prototype, assigned-to: claude
---

## Question

How do all the pieces fit together architecturally? What does the system diagram look like?

We need a high-level architecture overview showing:
- **Component layers**: Android App → Foreground Service → Ktor Server ↔ SvelteKit WebUI, with libtorrent JNI bridge on the native side
- **Data flow**: how a magnet link enters (WebUI form) → Ktor REST API → Kotlin TorrentSession → JNI → libtorrent, and how alerts flow back
- **Threading model**: which components run on which threads (main, background service, JNI native thread for libtorrent alerts)
- **Lifecycle**: app start → foreground service init → Ktor server bind → libtorrent session restore → WebUI ready

This is a prototype ticket because the answer should include a concrete diagram (ASCII or Mermaid) showing the component interactions, not just a description.

Blocked on libtorrent Android lifecycle (ticket 03) — the threading and persistence model depends on how libtorrent behaves on Android.

## Answer

### System architecture diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        ANDROID OS LAYER                             │
│                                                                     │
│  ┌──────────────┐    ┌──────────────────────────────────────────┐   │
│  │ MainActivity │    │           ForegroundService              │   │
│  │ (Main Thread)│    │           (Background Thread)            │   │
│  │              │    │                                          │   │
│  │ [REMOVED:   │    │  ┌─────────────┐   ┌──────────────────┐  │   │
│  │  Compose UI]│    │  │ Ktor Server │   │ TorrentSession   │  │   │
│  │              │    │  │ (HTTP + WS) │◄─►│ (Kotlin singleton)│  │   │
│  │ SvelteKit   │    │  │             │   │                  │  │   │
│  │ WebUI in    │    │  │  /api/*     │   │  nativeInit()    │  │   │
│  │ in-app      │    │  │ /ws/*       │   │  nativeAddMagnet │  │   │
│  │ WebView?    │    │  └──────┬──────┘   │  ...             │  │   │
│  │              │    │         │          └────────┬─────────┘  │   │
│  └──────┬───────┘    │         │                  │             │   │
│         │            │         ▼                  ▼             │   │
│         │            │  ┌─────────────┐     ┌──────────────┐   │   │
│         │            │  │EventBus     │     │ AlertDispatcher│   │
│         │            │  │(singleton)  │     │ (2s coroutine) │   │
│         │            │  └──────┬──────┘     └───────┬────────┘   │   │
│         │            │         │                    │             │   │
│         └────────────┼─────────┼────────────────────┤             │   │
│                      │         │                    │             │   │
│                      ▼         ▼                    ▼             │   │
│              ┌─────────────────────────────────────────────┐       │   │
│              │            JNI BRIDGE (torrent_jni.cpp)      │       │   │
│              │          global mutex g_mutex                │       │   │
│              └─────────────────────┬───────────────────────┘       │   │
│                                    │                               │   │
└────────────────────────────────────┼───────────────────────────────┘   │
                                     │                                   │
              ┌──────────────────────┴───────────────────────┐           │
              │           libtorrent C++ (native)             │           │
              │                                               │           │
              │  session::add_torrent()    pop_alerts()       │           │
              │  handle.pause()          internal threads:    │           │
              │  handle.resume()         DHT, tracker, peer  │           │
              └───────────────────────────────────────────────┘           │
                                                                           │
┌─────────────────────────────────── EXPRESSVPN (app-specific routing) ─────┘
│  All traffic from our process → VPN tunnel (torrent P2P + Ktor responses)
│
┌─────────────────── LOCAL NETWORK ──────────────────────────────────────────┐
│                                                                             │
│  Laptop Browser: http://<device-lan-ip>:8080                               │
│       │                                                                     │
│       │  HTTP: serves static files (SvelteKit bundle)                       │
│       │  WS:   /ws/progress (real-time updates)                             │
│       ▼                                                                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Request flow: add a magnet link

```
Browser                 Ktor                  TorrentSession           libtorrent JNI    libtorrent C++
   │                     │                         │                       │                  │
   │ POST /api/torrents  │                         │                       │                  │
   │ { magnet: "magnet:" }│                        │                       │                  │
   ├────────────────────►│                         │                       │                  │
   │                     ├─ nativeAddMagnet() ────►│                       │                  │
   │                     │                         ├─ add_torrent() ──────►│                  │
   │                     │                         │                       ├─ handle         │
   │                     │                         │                       │                  │
   │                     │                         │  alert: torrent_added                │
   │                     │                         │◄──────────────────────┤                  │
   │                     │                         │                       │                  │
   │  201 { id, name }   │                         │                       │                  │
   │◄────────────────────┤                         │                       │                  │
   │                     │                         │                       │                  │
   │  WS push:            │                         │                       │                  │
   │  { event: "torrent_added", id, name }          │                       │                  │
   │◄────────────────────┤                         │                       │                  │
```

### Status update flow: periodic progress + state changes

```
libtorrent C++          JNI (pop_alerts)         AlertDispatcher       EventBus        Ktor WS            Browser
    │                         │                        │                   │                 │                │
    │  state_changed_alert ──►│                        │                   │                 │                │
    │  error_alert           │  every 2s:             │                   │                 │                │
    │  tracker_reply_alert   │  popAlerts()           ├──────────────────►│                 │                │
    │                        ├─ getAllTorrentStatus() ┤  post(StateEvent) │                 │                │
    │                        │  (jlongArray per torrent)│                  │                 │                │
    │                        │                        │                   ├────────┬────────┤                 │
    │                        │                        │                   │        │        │                 │
    │                        │                        ▼                   ▼        │        │                 │
    │                        │                  ViewModel              TorrentViewModel                │
    │                        │                   (UI state)           MutableStateFlow             │
    │                        │                        │                  │        │        │                 │
    │                        │                        │                  │        │        ├──────────────►│
    │                        │                        │                  │        │        │  { event: "state_changed" │
    │                        │                        │                  │        │        │            id, newState }│
    │                        │                        │                  │        │        ◄───────────────┤
    │                        │                        │                  │        └── push batch every 2s            │
    │                        │                        │                  │        ◄───────────────────────────────┤
```

### Threading model

| Thread | Runs | Responsibility |
|--------|------|----------------|
| **Main (UI)** | MainActivity, ViewModel state collection | Compose UI rendering, user input |
| **Service (background)** | ForegroundService, Ktor Server, TorrentSession singleton | HTTP/WS request handling, torrent lifecycle management |
| **AlertDispatcher coroutine** | Dedicated `Dispatchers.IO` coroutine in ForegroundService | 2-second poll of `nativePopAlerts()` + `getAllTorrentStatus()`, categorize alerts, post to EventBus |
| **JNI (native)** | `torrent_jni.cpp` — all calls serialized by `g_mutex` | libtorrent API invocations (add, pause, resume, remove, status queries) |
| **libtorrent internal** | Native C++ threads (DHT, tracker, peer connections) | P2P networking, piece hashing, metadata download |
| **Ktor internal** | Ktor's thread pool (coroutine-based) | HTTP request handling, WebSocket frame processing |

**Critical constraint:** All libtorrent API calls go through the JNI global mutex `g_mutex`. This means:
- Only one native call executes at a time, regardless of which Kotlin thread initiated it
- The AlertDispatcher's 2-second poll and user-initiated actions (pause/resume) compete for the same lock
- For MVP this is acceptable — torrent operations are infrequent and fast; the lock contention is negligible
- For full feature set: consider per-torrent mutex or async alert callback to avoid blocking status queries during add/remove operations

### Lifecycle sequence

```
App process starts
    │
    ├─ 1. MainActivity.onCreate()
    │      ├─ System.loadLibrary("torrent-jni")        // loads libtorrent.so
    │      └─ Start ForegroundService
    │
    ├─ 2. ForegroundService.onStartCommand()
    │      ├─ TorrentSession.init(context)
    │      │     └─ nativeInit() → creates lt::session
    │      │           with alert_mask covering:
    │      │           error | storage | status | tracker |
    │      │           connect | peer | perf_warning
    │      ├─ AlertDispatcher.start() → launches 2s coroutine
    │      └─ KtorServer.start(port = config.port)
    │            └─ binds 0.0.0.0:<port>
    │            ├─ static routing → assets/ (SvelteKit bundle)
    │            ├─ /api/* → REST handlers
    │            └─ /ws/* → WebSocket handler
    │
    ├─ 3. [POST-MVP] TorrentSession.restoreState()
    │     └─ load saved session state from internal storage
    │         → libtorrent auto-resumes active torrents
    │
    └─ 4. WebUI ready — browser navigates to http://<device-ip>:<port>
```

### Component interaction summary

| Component | Runs on | Talks to | Talked by |
|-----------|---------|----------|-----------|
| SvelteKit WebUI (browser) | Laptop browser | Ktor `/api/*`, Ktor `/ws/*` | User navigation, JS fetch/WebSocket |
| Ktor Server | ForegroundService thread pool | TorrentSession (REST), AlertDispatcher (WS push) | Browser HTTP/WebSocket |
| TorrentSession | ForegroundService thread pool | JNI (`native*` calls), EventBus (post) | Ktor REST handlers, AlertDispatcher |
| AlertDispatcher | Dedicated coroutine (`Dispatchers.IO`) | JNI (`nativePopAlerts`, `nativeGetAllTorrentStatus`), EventBus (post) | ForegroundService startup |
| EventBus | In-process singleton | ViewModel (observe), AlertDispatcher (post) | AlertDispatcher, consumed by ViewModels |
| JNI bridge (`torrent_jni.cpp`) | Native (serialized by `g_mutex`) | libtorrent C++ session & handles | TorrentSession, AlertDispatcher |
| libtorrent C++ | Native internal threads | Filesystem (SSD downloads), Network (P2P, trackers) | JNI bridge |
| ExpressVPN | OS-level VPN service | Routes our app's sockets through tunnel | Android network stack |

### Key architectural decisions reflected in the diagram

1. **Single Ktor port** (from ticket 04): static files + REST + WS all on one port. Frontend uses relative paths — no discovery mechanism needed.

2. **AlertDispatcher consolidates polling** (from ticket 05): one 2-second coroutine replaces the per-torrent polling loop. Drains alerts AND collects status in a single JNI call cycle.

3. **Mutex serialization** (from ticket 03): all libtorrent calls go through `g_mutex`. Simple and correct for MVP; documented as a scalability concern for full feature set.

4. **EventBus decouples engine from UI** (existing pattern): AlertDispatcher posts to EventBus, ViewModels observe. Ktor WebSocket handler reads from the same event stream — no duplicate data flow.

5. **ForegroundService is the runtime anchor** (from ticket 01): survives screen-off, receives `BOOT_COMPLETED` to restart. Ktor + TorrentSession + AlertDispatcher all live here.

6. **ExpressVPN routes entire process** (from ticket 01): all sockets from our app — Ktor HTTP responses, libtorrent P2P — go through VPN tunnel. WebUI requests from laptop reach Ktor via LAN IP; responses route through VPN.

## Comments
- The architecture is intentionally simple: one foreground service owns everything (Ktor, TorrentSession, AlertDispatcher). No microservices, no separate processes. This matches the "low resource footprint" sustainability requirement.
- The JNI mutex is the single serialization point — it works for MVP but becomes a bottleneck if we add per-torrent fine-grained operations (selective file download, individual peer management) in post-MVP phases.
