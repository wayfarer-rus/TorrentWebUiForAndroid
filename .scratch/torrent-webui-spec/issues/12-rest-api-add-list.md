---
Type: task
Status: ready-for-agent
Labels: wayfinder:task, assigned-to: claude, milestone:m2
---

# 12 — REST API: add torrent + list torrents

**What to build:** Two HTTP endpoints that let a browser client add a new magnet link and see the full torrent list with live state — the "see something" operations that make the WebUI feel responsive.

**Blocked by:** 10 (Ktor server running), 11 (EventBus available for event-driven updates).

**Status:** ready-for-agent

- [ ] `POST /api/torrents/magnet` accepts a JSON body `{"magnet": "magnet:?xt=..."}` (or query param), calls `TorrentSession.addMagnet()`, returns `200 {"id": <long>, "status": "ok"}` or `400` with error message
- [ ] `GET /api/torrents` returns the full torrent list as JSON array: each item includes `id`, `name`, `state`, `progress` (0.0–1.0), `downloadRate`, `uploadRate`, `peers`, `savePath`
- [ ] Response format matches the data already surfaced in the Compose UI's `TorrentStatus` (no regression — existing card still shows same fields)
- [ ] Add/list operations go through `TorrentSession` (no bypass of the existing JNI bridge)
- [ ] Error responses use consistent JSON shape: `{"error": "human-readable message"}`
- [ ] Content-Type is `application/json` on all responses
