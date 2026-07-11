---
Type: task
Status: resolved
Labels: wayfinder:task, assigned-to: claude, milestone:m2
---

# 13 — REST API: pause/resume/remove + WebSocket real-time updates

**What to build:** State-change endpoints (pause, resume, remove) so users can control individual torrents from the browser, plus a WebSocket at `/ws/progress` that pushes live torrent state and alert events to connected clients every ~1 second.

**Blocked by:** 12 (REST data model and JSON conventions established).

**Status:** resolved ✅

- [x] `PUT /api/torrents/{id}/pause` pauses the torrent, returns `200 {"status": "ok"}` or `404` if torrent not found
- [x] `PUT /api/torrents/{id}/resume` resumes the torrent, returns `200 {"status": "ok"}` or `404`
- [x] `DELETE /api/torrents/{id}?deleteFiles=true|false` removes the torrent (optionally deleting on-disk files), returns `200 {"status": "ok"}` or `404`
- [x] WebSocket handler at `/ws/progress`: accepts connections, sends JSON snapshots of all torrent states + any pending alerts every ~1 second
- [x] WebSocket message format: `{"type": "torrents", "data": [...]}` for state snapshots, `{"type": "alert", "data": {...}}` for alert events
- [x] WebSocket broadcasts to all connected clients (multiple browser tabs/devices should all receive updates)
- [x] WebSocket gracefully handles client disconnects (no crash, no memory leak from dangling subscribers)
- [x] WebSocket uses the same `TorrentSession` calls as the REST GET endpoint (single source of truth for status data)
- [x] Existing Compose UI continues to work — WebSocket is additive, doesn't replace the 1-second polling in TorrentViewModel (that can be refactored later)

**Implementation:** `TorrentServer.kt` implements all control endpoints (pause, resume, remove) and WebSocket at `/ws/progress`. WebSocket sends JSON snapshots every 1 second with proper disconnect handling. All endpoints return consistent JSON responses.
