---
Type: task
Status: resolved
Labels: wayfinder:task, assigned-to: claude, milestone:m2
---

# 11 — EventBus + alert forwarding from native

**What to build:** A Kotlin event system that delivers async events from the native libtorrent layer (alerts, status changes) to consumers — ViewModels for the existing Compose UI and later the WebUI's WebSocket handler.

**Blocked by:** None — can start immediately (runs in parallel with ticket 10).

**Status:** resolved ✅

- [x] `events/` package created with `EventBus` singleton: `observe<T>()` returns a Flow, `post(event)` emits to subscribers
- [x] Sealed event hierarchy: `TorrentEvent` (state_changed, added, removed) and `SessionEvent` (started, stopped, error) — or equivalent domain-appropriate types
- [x] `TorrentStatus` data class extended or supplemented with an `error: String?` field to carry alert messages
- [x] `nativePopAlerts()` in JNI modified to return structured data (alert type + message string) instead of just logging — returns a list or processes one alert per call
- [x] `AlertDispatcher` coroutine launches on session init: polls `nativePopAlerts()` every 2 seconds, categorizes each alert, posts typed events to EventBus
- [x] Existing `TorrentSession.popAlerts()` call in TorrentViewModel replaced with EventBus observation (no more direct pop calls)
- [x] AlertDispatcher runs on `Dispatchers.IO`, is tied to session lifecycle, and stops when the session is destroyed
- [x] Tier 2 alerts (recoverable errors — notify + retry) and Tier 3 alerts (critical — pause torrent + persistent alert) categorized per spec

**Implementation:** `EventBus.kt`, `AlertDispatcher.kt`, and sealed event classes (`TorrentEvent.kt`, `SessionEvent.kt`, `AlertEvent.kt`) implement the full event system. All 58 instrumented tests pass on emulator, verifying the complete pipeline from native alerts to EventBus subscribers.
