---
Type: research
Status: resolved
Labels: wayfinder:research, assigned-to: claude
---

## Question

What torrent events need WebSocket push vs REST polling? What's the event taxonomy?

We've decided on REST + WebSocket. Now we need to define:
- **WS push events**: which torrent state changes get pushed in real-time? (progress updates, speed changes, status transitions like "downloading" → "seeding", errors)
- **REST polling**: which data is fetched on-demand? (torrent details, file lists for selective download, settings)
- **Event granularity**: per-torrent events or batched? How often should progress updates fire?
- **Connection management**: what happens when the WS connection drops? Reconnect strategy?

This is blocked on MVP scope (ticket 01) because the event set depends on which features are in MVP.

## Answer

### Event taxonomy

Events fall into three delivery categories based on urgency and frequency:

**1. Immediate push (WebSocket, per-event)** — state changes and errors that the user needs to see instantly.

| Event | Trigger (libtorrent alert) | Payload |
|-------|---------------------------|---------|
| `torrent_added` | `torrent_added_alert` | `{ id, name, savePath }` |
| `torrent_removed` | `torrent_removed_alert` | `{ id }` |
| `state_changed` | `state_changed_alert` | `{ id, newState }` — e.g. `"downloading"` → `"seeding"` |
| `error` | `error_alert`, `storage_error_alert` | `{ id, message, code }` |
| `tracker_error` | `tracker_error_alert` | `{ id, trackerUrl, message }` |
| `metadata_received` | `metadata_received_alert` | `{ id }` — torrent ready to download |

These fire only when something changes. They're the primary real-time signal — a state change from "downloading" to "seeding" means the torrent finished, and that's worth pushing immediately even if progress was already at 100%.

**2. Periodic push (WebSocket, batched)** — progress and speed updates at a fixed interval.

- **`progress_update`** — sent every 2 seconds, batched as an array of `{ id, progress, downloadRate, uploadRate, peers, seeds }` for all active torrents.
- Not per-event: libtorrent doesn't fire a native alert for "progress changed by 0.1%". Polling the status API would work but generates unnecessary JNI calls. Instead, the `AlertDispatcher` (from ticket 03) runs on a 2-second schedule: it calls `nativePopAlerts()` to drain any pending alerts AND collects current status for all torrents, then pushes the batched snapshot over WebSocket.
- 2 seconds balances responsiveness against bandwidth — fast enough to feel live, slow enough that a hundred torrents don't flood the connection.

**3. REST (on-demand)** — data fetched by client action, not pushed.

| Endpoint | Purpose |
|----------|---------|
| `GET /api/torrents` | Full torrent list with current state (snapshot) |
| `GET /api/torrents/{id}` | Single torrent details (files, pieces, peers) |
| `POST /api/torrents/add` | Add magnet or URL |
| `PATCH /api/torrents/{id}` | Pause/resume/delete |
| `GET /api/session/stats` | Global upload/download totals, active connections |

REST is the fallback and the action channel. WebSocket carries push events; REST carries commands and snapshots.

### Why this split works for MVP (ticket 01 scope)

MVP features: add torrent, list with progress/speeds, pause/resume/delete, live updates.

- **WS push covers "live"**: state changes + 2s batched progress give the appearance of real-time updates without per-frame polling
- **REST covers "control"**: add/pause/resume/delete are user-initiated actions, naturally REST
- **No selective download in MVP**: file-level endpoints stay out of scope (post-MVP feature)
- **No categories/search in MVP**: those need REST endpoints but no WS events

### Connection management

Mobile networks are unreliable. The protocol must handle disruption gracefully:

- **Client-side reconnect**: on WS close, retry with exponential backoff (1s → 2s → 4s → max 30s). The UI falls back to REST polling while disconnected.
- **Server-side resilience**: Ktor's WS implementation should handle client disconnects cleanly (no crash, no resource leak).
- **State reconciliation**: when WS reconnects, the client sends `GET /api/torrents` to resync its local state. No event ordering or deduplication needed on the server — the client always gets a full snapshot on reconnect.
- **No heartbeat protocol**: simple TCP keepalive + client-side timeout is sufficient for local network.

### AlertDispatcher design (from ticket 03)

The Kotlin `AlertDispatcher` is the bridge between libtorrent's alert system and WebSocket push:

1. Runs on a dedicated coroutine (not the main thread)
2. Every 2 seconds: calls `nativePopAlerts()` to drain the alert queue
3. Categorizes each alert (state_change, error, tracker, peer, performance)
4. Posts categorized alerts to `EventBus` for ViewModel consumption
5. Simultaneously collects current status for all torrents via `nativeGetAllTorrentStatus()`
6. Ktor WebSocket handler reads from EventBus and pushes batched snapshots

This replaces the current polling approach (ticket 03 noted: "Kotlin polls `getTorrentStatus()` every 1 second via coroutines — works but generates unnecessary JNI calls"). The AlertDispatcher consolidates alert processing AND status collection into a single 2-second cycle.

### What's NOT pushed (intentional)

- **Peer connect/disconnect**: too frequent, not useful in MVP. Post-MVP: optional toggle for debug/troubleshooting.
- **Performance warnings**: useful for diagnostics, not for UI. Post-MVP: surface in a debug panel.
- **Tracker reply (success)**: implied by `state_changed` and periodic progress. Only errors need explicit push.
- **Piece downloading**: too granular, no UI value in MVP.

### Summary table

| Event | Delivery | Frequency | MVP? |
|-------|----------|-----------|------|
| torrent_added | WS push (immediate) | on-change | yes |
| torrent_removed | WS push (immediate) | on-change | yes |
| state_changed | WS push (immediate) | on-change | yes |
| error | WS push (immediate) | on-change | yes |
| tracker_error | WS push (immediate) | on-change | yes |
| metadata_received | WS push (immediate) | on-change | yes |
| progress/speed | WS push (batched) | every 2s | yes |
| torrent list | REST GET | on-demand | yes |
| add/pause/resume/delete | REST POST/PATCH | on-demand | yes |
| peer events | — | — | post-MVP |
| performance alerts | — | — | post-MVP |
| file-level ops | REST | on-demand | post-MVP |

## Comments
- The 2-second batched progress is the key design decision: it gives the appearance of real-time without per-frame JNI calls. State changes still fire immediately so users see "completed" → "seeding" the moment it happens.
- AlertDispatcher unifies alert processing and status collection — one coroutine, one schedule, both concerns served.
