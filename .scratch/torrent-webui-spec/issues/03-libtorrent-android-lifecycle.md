---
Type: research
Status: resolved
Labels: wayfinder:research, assigned-to: claude, blocked-by: 01
Blocked by: 01
---

## Question

How does libtorrent's session lifecycle work on Android? What are the constraints and pitfalls?

Before we can design the architecture, we need to understand:
- How libtorrent's `session` object behaves in Android's background thread model
- Alert handling: how to poll/process alerts without blocking the main thread
- Session persistence across process restarts (libtorrent's `save_resume_data`, session state serialization)
- Known issues with libtorrent on Android (JNI thread safety, NDK compatibility, resource limits)
- Whether the existing `torrent_jni.cpp` handles these correctly or needs changes

This is blocked on MVP scope (ticket 01) because the session model depends on what features are in MVP vs later phases.

## Answer

### Current state (from code review)

**Thread model:**
- `torrent_jni.cpp` uses a global mutex (`g_mutex`) to serialize all JNI calls
- All libtorrent operations (add, pause, resume, remove, status) are protected by this mutex
- This is simple and correct for the current single-session design

**Alert handling (gap):**
- `nativePopAlerts()` exists but only logs alerts — doesn't forward them to Kotlin
- No alert filtering by type (error, status change, peer connection, etc.)
- The Kotlin side has no way to react to async events (torrent completed, error occurred)

**Session persistence (gap):**
- No `save_resume_data` or session state serialization
- If the process dies, all torrent handles are lost (files on disk remain)
- No way to restore torrents after reboot — this blocks the "resilience across reboots" requirement

**Poling approach (suboptimal but works for MVP):**
- Kotlin polls `getTorrentStatus()` every 1 second via coroutines
- This works but generates unnecessary JNI calls
- For MVP this is acceptable; WebSocket push (ticket 05) will replace polling later

**Save path (needs change for MVP):**
- Currently uses `context.getExternalFilesDir("downloads")` — app-specific internal storage
- MVP requires SSD via USB OTG — path resolution is an implementation concern (ticket 06)

### libtorrent on Android: known constraints

**1. JNI thread safety:**
- libtorrent internally uses its own threads for DHT, tracker communication, etc.
- The JNI bridge must ensure all libtorrent calls happen on a consistent thread context
- The current mutex approach works but creates a bottleneck — all operations serialize through one lock

**2. Alert processing:**
- libtorrent posts alerts on an internal thread pool
- `session::pop_alerts()` is thread-safe and can be called from any thread
- For production: alerts should be processed on a dedicated background thread, not polled from the main thread

**3. Session persistence:**
- libtorrent supports `session::save_state()` and `session::load_state()` for state serialization
- This saves: torrent handles, DHT state, peer cache, settings
- Format: bencoded buffer (binary) — can be stored in SharedPreferences or a file
- On restart: load state → session automatically re-resumes torrents that were active

**4. Resource limits on Android:**
- Android imposes file descriptor limits (typically 1024-8192 depending on version)
- libtorrent opens one FD per peer connection + tracker + DHT
- Default settings (160 connections limit) may exceed Android's limit under heavy load
- Recommendation: reduce `connections_limit` to 50-80 for Android

**5. NDK compatibility:**
- libtorrent 2.x requires C++17 (already configured in CMakeLists.txt)
- No known NDK-specific issues with version 2.0.10
- Boost (headers-only) is already bundled — no system dependency

### Recommendations for spec

**For MVP:**
1. Keep the mutex-based serialization (simple, correct)
2. Add a minimal alert forwarding mechanism: `nativePopAlerts()` should return structured data (alert type + message) instead of just logging
3. Skip session persistence for MVP — document as a known limitation

**For full feature set:**
1. Implement `save_state()` / `load_state()` for reboot resilience
2. Move alert processing to a dedicated background thread
3. Reduce `connections_limit` to 80 for Android FD safety
4. Add a `nativeHasSessionState()` query to check if state was loaded

**Architecture implication:**
The Kotlin side needs an `AlertDispatcher` that:
- Calls `nativePopAlerts()` on a schedule (or via callback when alerts arrive)
- Categorizes alerts (error, status_change, peer_connection)
- Posts to EventBus for ViewModel consumption

This unblocks ticket 05 (torrent events taxonomy) — we now know alerts are the mechanism for real-time updates.

## Comments
