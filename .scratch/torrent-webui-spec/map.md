# Torrent WebUI Spec

## Destination

A spec for an Android torrent client with a reachable WebUI — using the Android device as host, routing torrent traffic through ExpressVPN (app-specific), and exposing a Transmission-like management interface over the local network. The spec covers architecture, API design, feature phases, and implementation risks.

**Formal spec document**: [spec.md](./spec.md)

## Notes

- **Domain**: Android app development, torrent clients, libtorrent JNI, Ktor Server, SvelteKit
- **Existing code**: `app/src/main/jni/torrent_jni.cpp` (libtorrent JNI bridge), basic Kotlin scaffolding
- **Constraint**: No Node.js tooling in Android Studio — SvelteKit builds on laptop, output dropped into `assets/`
- **Constraint**: No native Android UI needed — WebUI is the only user-facing surface
- **Sustainability requirements**: background running (screen-off), reboot resilience, low resource footprint
- **Phased rollout**: MVP first, then full feature set. May hit unsolvable problems — spec should surface risks early

## Implementation tickets (Milestone 2: Browser Control Proof)

- [10 — Ktor server foundation + static file serving](issues/10-ktor-server-foundation.md) — Ktor binds 0.0.0.0:8080, serves SvelteKit assets from `assets/www/`, 200 on `/`
- [11 — EventBus + alert forwarding from native](issues/11-eventbus-alert-forwarding.md) — `events/` package, EventBus singleton, AlertDispatcher coroutine, nativePopAlerts returns structured data
- [12 — REST API: add torrent + list torrents](issues/12-rest-api-add-list.md) — `POST /api/torrents/magnet`, `GET /api/torrents`
- [13 — REST API: pause/resume/remove + WebSocket real-time updates](issues/13-rest-api-control-plus-websocket.md) — `PUT /api/torrents/{id}/pause|resume`, `DELETE ...`, `/ws/progress` pushes live state
- [14 — SvelteKit frontend build pipeline](issues/14-sveltekit-frontend-build.md) — `web/` project with magnet input, torrent list, controls; builds to static assets
- [15 — End-to-end: WebUI controls torrents from browser](issues/15-end-to-end-webui-controls.md) — full user flow: add magnet, see live progress, pause/resume/remove from browser

## Decisions so far

- [MVP scope defined](issues/01-mvp-scope.md) — core torrent management (add via magnet/URL, list with live progress, pause/resume/delete) + networking boundary (torrents through ExpressVPN, WebUI on LAN only)
- [Spec structure defined](issues/02-spec-structure.md) — hybrid format in `docs/spec/` (architecture, api, implementation, rollout); describes what not how; no code examples to avoid drift
- [libtorrent Android lifecycle understood](issues/03-libtorrent-android-lifecycle.md) — mutex-based serialization works for MVP; alerts need forwarding (not just logging); session persistence deferred to full feature set; reduce connections_limit to 80 for Android FD safety
- [SvelteKit discovery resolved](issues/04-sveltekit-discovery.md) — no discovery needed; Ktor serves static files and API on same port, frontend uses relative paths (`/api/*`, `/ws/*`), origin-agnostic JS bundle
- [Torrent events taxonomy defined](issues/05-torrent-events-taxonomy.md) — immediate WS push for state changes/errors, batched 2s progress snapshots, REST for on-demand commands/snapshots; AlertDispatcher unifies alert processing + status collection
- [Architecture overview defined](issues/07-architecture-overview.md) — single ForegroundService owns Ktor + TorrentSession + AlertDispatcher; JNI mutex serialization; lifecycle: load lib → init session → start Ktor on 0.0.0.0; threading: main/UI, service, AlertDispatcher coroutine, JNI (serialized), libtorrent internal
- [Two networks conundrum resolved](issues/09-two-networks-ard.md) — ExpressVPN intercepts at kernel routing level (TUN interface), not socket level; bindSocket() cannot bypass; all process traffic goes through VPN — accepted because WebUI overhead is negligible and torrent P2P (libtorrent's own sockets) is independently routed; "WebUI on LAN" means reachability via LAN IP, not response path
- **Torrent engine**: libtorrent retained — existing JNI bridge (`torrent_jni.cpp`) stays as the torrent engine
- **Frontend**: SvelteKit — compiled to static assets served from Android; no framework tax in APK
- **Backend**: Ktor Server — single process serving WebUI static files + REST API
- **Communication**: REST + WebSocket — REST for commands, WS for real-time progress updates
- **Storage**: SSD via USB OTG for downloads, internal storage for metadata — path resolution is an implementation concern
- **Reachability**: WebUI stays on local network, torrent traffic routes through ExpressVPN (app-specific)
- **Sustainability**: Foreground service with live stats notification + BOOT_COMPLETED auto-restart
- **Auth**: Out of scope — local-network access is the security boundary

## Not yet specified

_Milestone 2 implementation tickets published (issues 10–15). Ready for agent work._

## Decisions made in conversation

- **Bandwidth/resource limits**: Deferred to full feature set. MVP uses libtorrent defaults (connection limit hardcoded at 80 for Android FD safety). No per-torrent or global speed caps in MVP.
- **Error handling**: Three-tier system — Tier 1 (transient, auto-retry by libtorrent), Tier 2 (recoverable, notify + retry via toast), Tier 3 (critical, pause + persistent alert). MVP implements Tier 2 + Tier 3.
- **Third-party dependencies**: Audited in ticket 08 — Ktor server, Kotlinx Serialization, SvelteKit (minimal npm), AndroidX platform APIs. Testing: JUnit + Turbine + MockK + Ktor test host.

## Out of scope

- Native Android UI development — WebUI is the only interface
- Authentication / user accounts — local network is the security boundary
- iOS or desktop port — Android-only host
- VPN provider abstraction — ExpressVPN is a fixed dependency (app-specific routing)
