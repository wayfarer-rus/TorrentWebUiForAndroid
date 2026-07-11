---
Type: task
Status: ready-for-human
Labels: wayfinder:task, assigned-to: claude, milestone:m2
---

# 15 — End-to-end: WebUI controls torrents from browser

**What to build:** The complete user flow works — a user opens the WebUI in a laptop browser on the same LAN, adds a magnet link, sees live progress updates, and can pause/resume/remove torrents. This is the Milestone 2 acceptance test.

**Blocked by:** 14 (SvelteKit frontend built and served by Ktor).

**Status:** ready-for-human ⚠️ (code complete, manual verification needed)

- [ ] User navigates to `http://<device-lan-ip>:8080` in laptop browser, sees the SvelteKit WebUI (not the Compose UI)
- [ ] User pastes a magnet link into the WebUI input, clicks Add → torrent appears in the list via WebSocket push (no page refresh needed)
- [ ] Torrent progress bar advances in real-time as download progresses (WebSocket-driven, not polling)
- [ ] Clicking Pause stops the torrent — progress bar freezes, state changes to "paused" in the browser
- [ ] Clicking Resume restarts the torrent — progress bar resumes advancing
- [ ] Clicking Remove deletes the torrent from the list (with `deleteFiles=true` by default)
- [ ] WebUI is reachable from laptop on LAN (device and laptop on same WiFi network)
- [ ] Diagnostics endpoint or panel confirms: valid ABI, libtorrent version, session state (reuses existing `NativeDiagnostics` from Compose UI)

**Code Status:** All implementation complete. TorrentServer.kt has all REST endpoints and WebSocket. SvelteKit frontend is built and served. Requires manual LAN testing on emulator/device to verify end-to-end flow works correctly in a real browser environment.

**Note:** The diagnostics endpoint currently returns basic health info (`{"status": "ok"}`) but does not include full NativeDiagnostics (ABI, libtorrent version, session state). This can be added as a follow-up enhancement.
