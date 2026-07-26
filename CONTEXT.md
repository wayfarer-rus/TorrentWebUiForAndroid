# Project Context

This file defines the domain vocabulary used across the Torrent WebUI project. Consistent terminology helps agents and contributors navigate the codebase and understand architecture decisions.

## Authentication

### Password
- The secret credential used to authenticate WebUI access; its accepted default is `start123`.

### Authentication (HTTP Basic)
- The authentication scheme used by the WebUI.
- Implemented via Ktor `Authentication` plugin with Basic Auth scheme.
- Username field is ignored; only password is validated.
- Credentials sent in base64 (not encrypted) — acceptable on LAN only.
- Browser renders a native credential dialog.

### AuthManager
- Interface that abstracts password storage and retrieval.
- Methods: `getPassword()`, `setPassword(newPassword)`.
- Production implementation: `DefaultAuthManager` (SharedPreferences-backed).
- Test implementation: `InMemoryAuthManager`.

### WebUI Principal
- Internal Ktor object representing an authenticated request.
- Created by the Basic Auth validate block when password matches.
- Used to mark requests as authenticated for route protection.

## WebUI

### Torrent WebUI
- The primary remote state and control interface, accessible via LAN browser after Android startup is ready.
- Built with SvelteKit 5 (runes), served by Ktor server from Android assets, and protected by HTTP Basic Authentication.
- It controls application-specific settings and torrent behavior, but not Android platform-permission onboarding. Milestone 4 limits those settings to storage.

### WebSocket Endpoint
- The authenticated `/ws/progress` stream that provides real-time torrent status updates and alerts to the WebUI.

### Android Startup Bootstrap
- The bare-bones native prerequisite flow that requests and verifies required Android platform permissions before the torrent service becomes usable.
- It does not own Consumer Onboarding or application-specific configuration.
_Avoid_: Android onboarding, setup wizard

### Consumer Onboarding
- The mandatory WebUI-owned first-use journey that establishes service readiness, an Approved Destination, and an explicit Password decision for a household user.
- Its completion is durable and distinct from Android Startup Bootstrap and later recovery flows.
_Avoid_: Android onboarding, permission onboarding

### Onboarding Readiness
- The non-technical Consumer Onboarding state reported as **Ready**, **Action needed on Android**, or **Service unavailable**.
- It exists only while the WebUI is reachable and never describes pre-bootstrap or WebUI bind failures.
_Avoid_: Daemon health, diagnostics

### Onboarding Completion
- The durable fact that Consumer Onboarding has been completed for an installation.
- Once established, later permission, storage, destination, or daemon failures never make the installation first-use again.
_Avoid_: Current readiness, browser-local completion

### Password Decision
- The durable Consumer Onboarding choice: **pending**, **changed**, or **deferred**.
- **Pending** is unresolved; **changed** and **deferred** can satisfy the Password step.

### Onboarding Marker
- The durable evidence that first-use migration has already classified an installation.
- An existing incomplete marker always wins over queue, destination, or Password evidence discovered later, preserving interrupted setup progress.

## Android UI

### Android Fallback Control
- The bare-bones Android surface provides Android Startup Bootstrap, daemon health, emergency transfer controls, WebUI Port configuration, and local Password Reset.
- It never duplicates the torrent queue, individual torrent controls, or Consumer Onboarding.

### MainActivity
- The Android app's bare-bones bootstrap, health, and fallback-control screen.
- It exposes Android platform capabilities and recovery controls, never Consumer Onboarding or torrent-management controls.

### Daemon Control Seam
- `DaemonControl` interface unifies session operations with lifecycle management (init/destroy).
- `TorrentSession` implements `DaemonControl` directly; `DaemonControlFactory` provides production and test entry points.
- `TorrentServer` and `TorrentViewModel` both talk through `DaemonControl`, so the native session can move behind a foreground service without changing callers.

### Queue Store
- `QueueStore` interface provides durable queue intent and per-torrent resume data storage.
- `FileQueueStore` uses atomic file replacement for durability; lives in app-private storage.
- Recovery records are never exposed through WebUI responses, notifications, or logs.

### Password Reset
- The local Android recovery action that restores the WebUI Password to `start123` without requiring the current Password or accepting a replacement.
- Physical access to the Android app is sufficient authority for this recovery action.
_Avoid_: Password change

## LAN (Local Area Network)

### Threat Model
- The app runs on a private LAN behind a router NAT.
- No public Internet exposure by default.
- HTTP Basic Auth credentials are base64-encoded (not encrypted).
- Acceptable for household privacy, not suitable for public Internet.

### Network Boundaries
- WebUI is accessible from devices on the same LAN and is not publicly exposed by default.
- VPN split tunneling is external deployment configuration, not app logic.

### WebUI Port
- The Android-owned TCP port on which the WebUI accepts LAN connections.
- It is configured and displayed only in Android Fallback Control, not in Consumer Onboarding or WebUI settings.
_Avoid_: WebUI setting, onboarding setting

### Emulator Acceptance
- A fully automated end-to-end validation run on an Android Virtual Device (AVD).
- It is Milestone 4's primary automated acceptance environment: real APK/JNI, Android permission flow, shared-storage paths, and emulator-local WebUI/API behavior.
- It exercises the real JNI/libtorrent session with a deterministic local fixture; fakes may supplement unit tests but cannot satisfy M4 end-to-end acceptance.
- Every run verifies teardown: daemon stopped, recovery records and test downloads removed, local fixture shut down, and no test credentials or torrent metadata left in logs.

### Physical-Device LAN Acceptance
- Validation on a physical Android device from a separate LAN browser, optionally including Termux/SSH path comparison.
- It is an optional final deployment check for M4; it confirms the operational path contract in the target environment but does not replace Emulator Acceptance.

## Service Lifecycle

### Torrent Daemon
- The foreground Android service that is the sole Android-side lifecycle owner of the native torrent session and WebUI server.
- Android UI and WebUI observe and control the daemon through the shared backend/domain model; neither UI owns the native session lifecycle.
- The WebUI remains reachable on the LAN while the daemon is active, even if `MainActivity` is backgrounded.
- The daemon stays active after its transfer queue becomes idle, so the LAN WebUI remains available; only the user’s explicit **Stop downloads** action stops it.

### System-Termination Recovery
- The daemon restores its persisted queue when Android terminates the torrent service and the user next opens the app.
- Torrents that were active before system termination are eligible to resume; an explicit user stop is never treated as system termination.
- Automatic recovery after device reboot is outside Milestone 3.

### Explicit Stop
- A user-requested stop is durable intent: automatic recovery must leave the torrent service stopped until the user starts it again.
- Android Force stop is equivalent to explicit stop. It must not trigger automatic recovery; the user must explicitly start downloads again.
- **Start downloads** resumes every healthy unfinished queue entry, including entries that were individually paused before the explicit stop. Entries with a recoverable error remain paused.

### Recovery Record
- The app-private record used to restore a queue entry after system termination.
- Contains typed queue metadata and opaque native resume data. It must not be exposed through the WebUI or written to logs, because it can contain magnet URIs or private tracker URLs.

### Daemon Notification
- The persistent foreground-service notification exposes aggregate, non-sensitive transfer state only: active-torrent count, overall progress, and aggregate transfer rate.
- Torrent names, magnet URIs, tracker data, and filesystem paths are never shown in the notification by default, including on the lock screen.
- It offers one action: **Stop downloads**. This records explicit-stop intent and removes the foreground service; individual torrent controls remain in the WebUI and Android UI.
- On Android 13+, notification permission is a prerequisite for starting the daemon, so its state and Stop action remain visible.

### Recovery Failure
- If native resume data is unreadable or rejected, the daemon restores the safe queue metadata as paused with a recoverable error.
- It neither crashes nor silently discards the record; the user can retry or remove it. Broader corrupted-state recovery is deferred to Milestone 9.

### Daemon Start Failure
- If native-session startup or restoration fails, the daemon stops cleanly and exposes a non-sensitive recoverable health error.
- It does not automatically retry in the background; a user-initiated **Start downloads** action retries startup.

### Safe Lifecycle Restart
- A restart in which the download storage remains available and the daemon can use its recovery records directly.
- Queue mutations are durable immediately; native resume data is atomically checkpointed at most every 30 seconds. A system termination may therefore lose no more than 30 seconds of transfer progress, never the queue or a previously valid record.

### Safe Stop
- An explicit **Stop downloads** request that persists queue intent and the latest resume checkpoint before the daemon exits.
- Persistence has a five-second deadline. On failure or timeout, the daemon remains running and exposes a recoverable error rather than claiming a safe stop.

### Storage Interruption
- An unexpected loss of download-storage availability, distinct from a safe lifecycle restart.
- In Milestone 3, the daemon pauses affected entries with a recoverable error and never treats the event as ordinary recovery.
- Automatic reconnection detection and downloaded-data verification before resuming are Milestone 9 responsibilities.

## Storage

### Save Path
- The directory where one torrent's files are downloaded.
- In Stage 1: app-private external storage (`/storage/emulated/0/Android/data/...`).
- In Stage 4+: the verified, copyable device filesystem path of that torrent's Approved Destination.

### Approved Destination
- A user-approved storage folder that has Android All Files Access and a verified, usable device filesystem path.
- Each torrent references one Approved Destination. Every verified selection persists in the reusable destination catalog and is removable only when no torrent references it. Its canonical, real, copyable filesystem path is its sole identity everywhere; generic SAF selections are not Approved Destinations.

### Latest Selected Destination
- The most recently chosen Approved Destination, used as the default for adding a new torrent.
- It does not change the destination of an existing torrent.

### Recommended Destination
- The real canonical `<primary storage volume>/Download/Torrents` path proposed during Consumer Onboarding.
- It becomes an Approved Destination only after user confirmation and backend validation.
_Avoid_: Default destination, storage alias

### Legacy Destination
- The real app-private download path retained by an existing torrent after an upgrade to the path-based destination model.
- It remains available only to preserve or move that torrent's data; no new torrent may select it.

### Torrent Data Move
- An explicit operation that relocates the downloaded and partial data of one torrent from its current Approved Destination to another.
- Initiation acknowledges `moving` only after the durable move record exists and the native engine accepts the request; completion, failure, retry, and cancellation are asynchronous state transitions.
- It pauses only that torrent while it copies and verifies target data, changes the destination only on success, and removes the source only afterward. Failure or cancellation retains the source and leaves the torrent paused with a recoverable error; a move is never implied by changing the Latest Selected Destination or another torrent's destination. Existing target files are never overwritten. A non-empty target enters `storage_conflict` and remains paused; automatic reuse is deferred until a native piece-verification flow exists.

### Move Interrupted
- The recoverable paused state after a Torrent Data Move is interrupted by process termination, reboot, storage loss, or All Files Access revocation.
- The app preserves both source and target data and requires an explicit retry or cancel; it never deletes either side automatically. Cancel is available only from this state: it clears the recovery record, leaves both locations intact, and keeps the torrent paused.
- A legacy move record gains a QueueId only when exactly one durable Queue Entry has the recorded source path. An ambiguous record remains interrupted and unassociated; the app never guesses a torrent association.

### Verified Device Path
- The actual absolute filesystem path for an Approved Destination, confirmed usable by the app and intended to be copied into device SSH sessions and operational tooling.
- It is the destination's sole identity: never inferred, fabricated, substituted with a document URI, or accompanied by a label or alias.

### Path Visibility
- The authenticated WebUI/API and explicit user-requested diagnostics may show a Verified Device Path for operational use.
- Routine Android logs, notifications, broadcasts, and generic error text never include it.

### All Files Access
- The broad Android storage permission required for the path-based destination model to access the real device filesystem path used by torrent operations.
- It is a prerequisite for approving a destination, but it does not itself confine the product to approved folders; backend validation does that. Denial blocks daemon startup; revocation pauses affected torrents until permission is restored and the user explicitly resumes them.

### Storage Volume
- A real shared or external device filesystem root reported by the backend as a possible starting point for destination selection.
- It is shown in the WebUI by its actual path, not an inferred or cosmetic alias. Approved Destinations must have a canonical writable path within one.

### WebUI Directory Browser
- The browser-based destination selector that lists Storage Volumes, accepts pasted device paths, and navigates only backend-validated directories.
- It never invents paths or delegates selection to an Android file picker after All Files Access is available.

## Relationships

```
┌─────────────┐     HTTP Basic Auth      ┌──────────────┐
│   Browser   │ ◄──────────────────────► │  Ktor Server │
│  (WebUI)    │                          │  (Android)   │
└─────────────┘                          └──────────────┘
                                               │
                                          authManager
                                               │
                                               ▼
                                        ┌──────────────┐
                                        │ SharedPreferences │
                                        │  (webui_password) │
                                        └──────────────┘

┌─────────────┐     Direct call         ┌──────────────┐
│  Android UI │ ◄──────────────────────► │  AuthManager │
│ (MainActivity)│                        │ (interface)  │
└─────────────┘                          └──────────────┘
```

## Out of Scope (Documented Elsewhere)

- **VPN split tunneling:** External deployment configuration, not app logic. See ADR-005.
- **SAF document destinations:** Not part of the path-based destination model; Approved Destinations use canonical filesystem paths. See ADR 0015 and ADR 0023.
- **Session persistence:** Stage 3 feature, not implemented in Stage 1-2. See ADR-008.
- **HTTPS/TLS:** Milestone 5+ concern, not implemented in Stage 2.
