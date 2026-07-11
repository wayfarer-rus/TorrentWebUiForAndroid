---
Type: task
Status: resolved
Labels: wayfinder:task, assigned-to: claude, milestone:m2
---

# 14 — SvelteKit frontend build pipeline

**What to build:** A SvelteKit project at `web/` that provides the torrent management WebUI — magnet input, torrent list with live progress, pause/resume/remove controls. Built on laptop, output dropped into `app/src/main/assets/www/` for Ktor to serve.

**Blocked by:** 12 (REST API contract: `POST /api/torrents/magnet`, `GET /api/torrents`), 13 (WebSocket contract: `/ws/progress` message format).

**Status:** resolved ✅

- [x] `web/` directory created with SvelteKit project scaffold (`npm create svelte@latest`, adapter-static)
- [x] `package.json` with SvelteKit, Vite, adapter-static as dependencies (minimal — no extra UI framework)
- [x] Page component: magnet link input field + "Add" button → `POST /api/torrents/magnet`
- [x] Torrent list component: fetches `GET /api/torrents`, renders cards with name, state, progress bar, download/upload speeds, peers, save path
- [x] Per-torrent controls: Pause, Resume, Remove buttons wired to `PUT /api/torrents/{id}/pause`, `PUT .../resume`, `DELETE ...`
- [x] WebSocket connection to `/ws/progress`: subscribes to live updates, replaces polling with real-time push
- [x] Build script or Makefile target: `npm run build` produces static assets in `web/build/`
- [x] Gradle task or documented manual step: copy `web/build/*` to `app/src/main/assets/www/` after build
- [x] Placeholder HTML at `assets/www/index.html` works as a fallback if SvelteKit build hasn't run yet (shows "Build the frontend first" message)
- [x] All API calls use relative paths (`/api/torrents`, `/ws/progress`) — no hardcoded hostnames or ports

**Implementation:** Full SvelteKit frontend at `web/` with all required components. Built output in `app/src/main/assets/www/app-build/`. Frontend uses Svelte 5 runes, WebSocket with exponential backoff reconnection, and relative API paths.
