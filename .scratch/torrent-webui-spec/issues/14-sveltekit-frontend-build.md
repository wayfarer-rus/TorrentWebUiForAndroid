---
Type: task
Status: ready-for-agent
Labels: wayfinder:task, assigned-to: claude, milestone:m2
---

# 14 — SvelteKit frontend build pipeline

**What to build:** A SvelteKit project at `web/` that provides the torrent management WebUI — magnet input, torrent list with live progress, pause/resume/remove controls. Built on laptop, output dropped into `app/src/main/assets/www/` for Ktor to serve.

**Blocked by:** 12 (REST API contract: `POST /api/torrents/magnet`, `GET /api/torrents`), 13 (WebSocket contract: `/ws/progress` message format).

**Status:** ready-for-agent

- [ ] `web/` directory created with SvelteKit project scaffold (`npm create svelte@latest`, adapter-static)
- [ ] `package.json` with SvelteKit, Vite, adapter-static as dependencies (minimal — no extra UI framework)
- [ ] Page component: magnet link input field + "Add" button → `POST /api/torrents/magnet`
- [ ] Torrent list component: fetches `GET /api/torrents`, renders cards with name, state, progress bar, download/upload speeds, peers, save path
- [ ] Per-torrent controls: Pause, Resume, Remove buttons wired to `PUT /api/torrents/{id}/pause`, `PUT .../resume`, `DELETE ...`
- [ ] WebSocket connection to `/ws/progress`: subscribes to live updates, replaces polling with real-time push
- [ ] Build script or Makefile target: `npm run build` produces static assets in `web/build/`
- [ ] Gradle task or documented manual step: copy `web/build/*` to `app/src/main/assets/www/` after build
- [ ] Placeholder HTML at `assets/www/index.html` works as a fallback if SvelteKit build hasn't run yet (shows "Build the frontend first" message)
- [ ] All API calls use relative paths (`/api/torrents`, `/ws/progress`) — no hardcoded hostnames or ports
