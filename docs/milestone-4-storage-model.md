# Milestone 4 — Path-Based Storage Model Specification

## Status and Intent

Milestone 4 replaces the proof-of-concept app-private global save directory with durable, per-torrent, real filesystem destinations. The product must expose exactly the canonical device path it uses, so an authenticated user can copy it into SSH tooling. This is a path-based model, not a generic SAF model.

The WebUI is the primary remote control and application-settings surface. Android owns platform-permission onboarding at app startup only.

## In Scope

- Android startup requests and verifies `MANAGE_EXTERNAL_STORAGE` before storage operations are enabled.
- WebUI discovery and selection of validated shared/external filesystem directories.
- Persistent reusable destination catalog; canonical path is the sole destination identity.
- Per-torrent destination on add and explicit one-torrent-at-a-time moves.
- Durable destination, queue, and move-journal recovery data.
- Legacy preservation of existing app-private downloads.
- Authenticated storage APIs and WebUI states for unavailable storage and permission loss.
- Emulator-based E2E acceptance; optional physical-device Termux/SSH confirmation.

## Out of Scope

- Generic SAF/document-URI destinations.
- Destination labels, aliases, opaque destination IDs, or synthetic paths.
- Port and torrent-parameter settings screens.
- Multi-torrent/bulk moves, automatic cleanup after an interrupted move, automatic resume after permission restoration, or a general-purpose remote file manager.

## Ownership and Startup

1. `MainActivity` requests required Android platform permissions during startup, including All Files Access.
2. Permission denial blocks daemon startup and presents native retry/settings guidance.
3. After permission readiness, the daemon may start the WebUI and native session.
4. The WebUI manages application-specific storage settings; it never launches an Android picker.
5. If All Files Access is revoked while running, affected torrents pause, adds/moves are rejected, and the authenticated WebUI reports `storage_permission_required`. Recovery requires permission restoration and explicit user resume.

## Destination Model

### Approved Destination

An Approved Destination is an existing, writable directory whose canonical path lies beneath a backend-reported shared or external Storage Volume. Its canonical path is its only persisted and public identity.

The backend must reject paths that are absent, non-directory, non-writable, app-private, system-owned, outside the reported storage volumes, or that resolve through a symlink outside the selected storage volume. Generic SAF `content://` URIs are never destinations.

A verified path is inserted into the reusable catalog and becomes the Latest Selected Destination. The catalog retains all verified paths and may remove a path only when no torrent references it.

### WebUI Storage Controls

Authenticated storage controls expose:

- reported Storage Volume roots as real paths;
- child-directory browsing under those roots;
- pasted absolute paths for validation;
- the reusable destination catalog;
- the latest-selected default;
- per-torrent destination selection and move actions.

The UI must show canonical paths verbatim. It must not replace them with labels or aliases. It may not browse or act outside backend-validated storage roots.

## Torrent Operations

### Add

A new torrent receives an explicit canonical `destinationPath`. The WebUI preselects the Latest Selected Destination but the user may select or validate another path. The selected path is persisted with the queue entry before success is returned.

If the target already contains data, the operation never overwrites it. Existing data is reusable only after normal libtorrent piece verification; otherwise the torrent remains paused with a recoverable conflict/error.

### Move

A Torrent Data Move targets exactly one torrent:

1. reject when storage permission is unavailable or another move is active for that torrent;
2. persist a move journal before changing data;
3. pause only the target torrent and report `moving`;
4. copy/move through the native engine or a narrow typed native operation, then verify target data;
5. atomically persist the new queue destination only after verification succeeds;
6. remove the source only after that durable destination update succeeds;
7. resume only on explicit user action where the torrent was previously paused or recovery requires it.

Failure or cancellation preserves the source and target data, retains the journal, and leaves the torrent paused with a recoverable status. A process kill, reboot, USB loss, or permission revocation produces `move_interrupted`; startup inspects both locations and requires an explicit retry or cancel. No automatic deletion or guessed recovery is permitted.

### Legacy Destinations

Existing app-private M3 paths are retained as Legacy Destinations for their currently associated torrents. Their real paths remain visible and each torrent may continue or move to an Approved Destination. New torrents cannot select a Legacy Destination.

## Shared Backend and JNI Boundary

Kotlin remains the owner of domain state and persistence; native code remains the owner of libtorrent objects and native move operations. The boundary adds small typed operations/models, not raw pointers or JSON blobs:

- `StorageVolume(path)`
- `DirectoryValidation(path, canonicalPath, writable, rejectionReason)`
- `TorrentDestination(destinationPath)`
- `MoveState(torrentId, sourcePath, targetPath, phase, recoverableError)`
- `MoveResult(status, recoverableError)`

`DaemonControl`, `TorrentSessionOps`, `QueueStore`, `TorrentStatus`, the WebUI DTOs, and their test implementations must evolve together. `nativeSetSavePath` is insufficient because M4 requires a path per torrent; adding a torrent and moving storage need dedicated typed JNI calls. The native layer must not log paths routinely.

## Persistence and Recovery

Queue intent becomes versioned and persists, per torrent: magnet metadata, pause intent, canonical destination path, and resume data reference. Destination catalog and latest-selected path persist atomically in app-private configuration. The move journal persists source, target, phase, and target torrent identity atomically before data movement.

Startup validates every referenced path and All Files Access. An unavailable destination pauses only its affected torrents. Revalidation occurs on app/daemon startup; restoration always requires explicit resume. A destination may not be removed while a queue entry or move journal references it.

## Authenticated API Shape

All storage endpoints require the existing WebUI password authentication. Exact route names may follow existing Ktor conventions, but the model must support:

- storage readiness/permission state;
- volume roots and validated directory children;
- validate/add/remove/list destination paths;
- latest-selected destination;
- add torrent with `destinationPath`;
- per-torrent destination status;
- start/status/retry/cancel one move.

Torrent responses and WebSocket snapshots expose the canonical per-torrent path. Paths may appear in authenticated API/WebUI responses and explicit diagnostics only—never routine Android logs, notifications, broadcasts, generic error text, or recovery records exposed to the browser.

## UI States

- `ready` — permission available; storage operations enabled.
- `storage_permission_required` — native startup blocked or runtime permission revoked; adds/moves disabled.
- `destination_unavailable` — referenced canonical path is not currently usable; affected torrent paused.
- `moving` — one torrent is changing destination.
- `move_interrupted` — source and target retained; explicit retry/cancel required.
- `storage_conflict` — existing target data needs verification or is incompatible; torrent remains paused.

The default password remains `start123`; changing it remains optional. The WebUI continually reminds users while that default remains unchanged.

## Acceptance Criteria

### Automated emulator E2E (required)

- Real APK, JNI/libtorrent, Ktor server, and WebUI assets run on an AVD.
- Android permission-ready, deny, and runtime-revocation states are exercised.
- Host-side authenticated HTTP/browser tests reach the emulator through ADB forwarding.
- Reported canonical paths match ADB-observed emulator directories.
- Add with a destination, destination reuse, per-torrent differences, conflict verification, and one-torrent move are covered.
- Interrupted move recovery, permission loss, unavailable storage simulation, and explicit retry/cancel are covered deterministically.
- Each test cleans daemon/server state, queue records, journals, test files, fixtures, and credentials.

### Optional physical-device deployment check

On a target device, compare the authenticated WebUI path with the same path observed in Termux/SSH, then validate add, move, and permission-loss recovery. Record only actual results in `TEST_REPORT.md`.

## Delivery Batches

1. Domain/persistence models and migration of legacy queue data.
2. Android permission readiness and backend path-validation services.
3. Typed JNI per-torrent add/move operations and daemon recovery integration.
4. Ktor APIs and WebUI storage controls.
5. Unit/instrumentation/emulator E2E suite, documentation, code review, and optional device check.

Each batch must be independently reviewable, avoid a broad rewrite, remain within the 300-second request budget, and leave no fabricated validation claims.
