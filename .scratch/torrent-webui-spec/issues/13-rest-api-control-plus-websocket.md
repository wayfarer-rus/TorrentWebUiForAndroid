---
Type: task
Status: ready-for-agent
Labels: wayfinder:task, assigned-to: claude, milestone:m2
---

# 13 — REST API: pause/resume/remove + WebSocket real-time updates

**What to build:** State-change endpoints (pause, resume, remove) so users can control individual torrents from the browser, plus a WebSocket at `/ws/progress` that pushes live torrent state and alert events to connected clients every ~1 second.

**Blocked by:** 12 (REST data model and JSON conventions established).

**Status:** ready-for-agent

- [ ] `PUT /api/torrents/{id}/pause` pauses the torrent, returns `200 {"status": "ok"}` or `404` if torrent not found
- [ ] `PUT /api/torrents/{id}/resume` resumes the torrent, returns `200 {"status": "ok"}` or `404`
- [ ] `DELETE /api/torrents/{id}?deleteFiles=true|false` removes the torrent (optionally deleting on-disk files), returns `200 {"status": "ok"}` or `404`
- [ ] WebSocket handler at `/ws/progress`: accepts connections, sends JSON snapshots of all torrent states + any pending alerts every ~1 second
- [ ] WebSocket message format: `{"type": "torrents", "data": [...]}` for state snapshots, `{"type": "alert", "data": {...}}` for alert events
- [ ] WebSocket broadcasts to all connected clients (multiple browser tabs/devices should all receive updates)
- [ ] WebSocket gracefully handles client disconnects (no crash, no memory leak from dangling subscribers)
- [ ] WebSocket uses the same `TorrentSession` calls as the REST GET endpoint (single source of truth for status data)
- [ ] Existing Compose UI continues to work — WebSocket is additive, doesn't replace the 1-second polling in TorrentViewModel (that can be refactored later)
