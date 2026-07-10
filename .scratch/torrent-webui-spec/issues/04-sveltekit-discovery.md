---
Type: research
Status: resolved
Labels: wayfinder:research, assigned-to: claude
---

## Question

How does the SvelteKit frontend discover and connect to the Ktor server at runtime?

The WebUI runs as static files served by Ktor inside the Android app. The frontend needs to know:
- **Hostname**: always `localhost` or `127.0.0.1` since it's served locally? Or does the Ktor server bind to `0.0.0.0` and the frontend connects from outside?
- **Port**: hardcoded in both server config and frontend, or dynamic/discoverable?
- **WebSocket URL**: how does the SvelteKit app construct the WS endpoint relative to the current page?

Options:
- **Hardcoded port**: Ktor listens on a fixed port (e.g. 8080), frontend uses relative paths (`/api/...`, `/ws/...`). Simplest, but port conflicts possible.
- **Dynamic port + config endpoint**: Ktor picks a random port, exposes `/api/config` that returns the base URL. Frontend calls this first, then uses the returned URL for all subsequent requests.
- **Environment variable at build time**: SvelteKit reads a config file embedded in the APK that specifies the server URL.

What approach fits best given our constraints (Android-hosted, no external discovery service)?

## Answer

**No discovery needed — relative paths work because Ktor serves both static files and API on the same origin.**

### The key insight

Ktor serves the compiled SvelteKit static assets AND the REST API on the **same port** (decided in ticket 02 — single Ktor process handling both). The browser loads `http://<device-lan-ip>:<port>/index.html` from Ktor, and all subsequent JS fetch calls to `/api/torrents`, `/ws/progress`, etc. automatically resolve to the same `<device-lan-ip>:<port>` origin.

This is standard browser behavior — relative paths inherit the current page's scheme, hostname, and port. No configuration, no discovery endpoint, no hardcoded URLs in the JS bundle.

### How it works end-to-end

1. User opens `http://192.168.1.50:8080` in laptop browser
2. Ktor serves `index.html` + JS/CSS bundle from Android assets
3. JS makes `fetch('/api/torrents')` → browser sends request to `http://192.168.1.50:8080/api/torrents`
4. Ktor routes `/api/*` to REST handlers, `/ws/*` to WebSocket handlers
5. Everything works regardless of what IP:port the user navigates to

### What this means for SvelteKit build config

- **No environment variables needed** at build time — the JS bundle is origin-agnostic
- **No runtime config fetch** — eliminates a network round-trip on every page load
- SvelteKit's `adapter-static` (or equivalent) produces plain HTML/CSS/JS that makes zero assumptions about the backend
- Ktor's static file serving (`StaticContent` routing) reads from Android `assets/` directory

### Addressing the original concerns

**Hostname**: The frontend doesn't specify a hostname — it uses relative paths. The browser resolves based on the URL bar.

**Port**: Ktor binds to a configurable port (default 8080, overridable via config). The frontend doesn't need to know the port — relative paths inherit it.

**WebSocket URL**: `new WebSocket('/ws/progress')` resolves to `ws://<device-ip>:<port>/ws/progress`. Ktor's WebSocket support handles the upgrade on the same port. No `ws://` vs `wss://` concern since this is local HTTP (no HTTPS/mTLS in MVP per ticket 01).

### Not needed: config endpoint, dynamic port, build-time env vars

A `/api/config` discovery endpoint adds complexity for no benefit — there's nothing dynamic to discover. The API is always at `/api/*` on the same origin the page loaded from. A dynamic port would require discovery, which we don't need. Build-time env vars are irrelevant since the bundle is origin-agnostic.

### Port conflict consideration

A fixed port (e.g., 8080) could theoretically conflict with another service on the device. Mitigation:
- Use a high, non-standard port (e.g., 8765) to avoid conflicts with common services
- Document the port in app settings so user can change it if needed
- This is acceptable for MVP — a production app might use port 0 (OS-assigned) + a well-known config, but we don't need that here

### Networking note (from ticket 01)

Ktor binds to `0.0.0.0` so the device is reachable from LAN. ExpressVPN app-specific routing applies to all traffic from our process — since Ktor handles both WebUI and API on the same port, all traffic from the app goes through the VPN tunnel. The WebUI is still reachable from LAN because ExpressVPN routes based on source app, not destination — the laptop's requests to `<device-lan-ip>:<port>` reach Ktor normally, and responses route through VPN. Torrent P2P traffic uses libtorrent's own sockets (separate from Ktor) and is independently routed by ExpressVPN.

## Comments
- Single-port relative paths eliminate the discovery problem entirely. This is only possible because Ktor serves both static files and API — if they were separate services, discovery would be needed.
