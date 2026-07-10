---
Type: task
Status: ready-for-agent
Labels: wayfinder:task, assigned-to: claude, milestone:m2
---

# 10 — Ktor server foundation + static file serving

**What to build:** The Android app launches a Ktor HTTP server that serves the SvelteKit WebUI static assets and provides the backbone for all subsequent REST API and WebSocket work.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Ktor dependencies added to `build.gradle.kts` (server-core, netty-engine, content-negotiation, serialization-json, static-content, websockets)
- [ ] Ktor server configured to bind `0.0.0.0:8080` (port configurable via constant)
- [ ] Static file serving routes `/` and subpaths to files in `app/src/main/assets/www/` (serves `index.html`, JS/CSS bundle)
- [ ] Server starts automatically when the app process launches (integrated into existing MainActivity or a dedicated startup path)
- [ ] `GET /` returns 200 with the SvelteKit `index.html` (even if assets are minimal — a placeholder HTML is acceptable)
- [ ] App still launches and displays the existing Compose UI (WebUI does not yet replace it — Compose stays as fallback)
