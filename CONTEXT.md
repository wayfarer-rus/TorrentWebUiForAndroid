# Project Context

This file defines the domain vocabulary used across the Torrent WebUI project. Consistent terminology helps agents and contributors navigate the codebase and understand architecture decisions.

## Authentication

### Password
- The secret credential used to authenticate WebUI access.
- Stored in Android `SharedPreferences` under key `webui_password`.
- Default value: `start123`.
- Minimum length: 4 characters. No maximum (passphrases allowed).
- Changed via `POST /api/settings/password` or Android settings screen.
- Takes effect immediately on next auth check (no cache).

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
- The primary user interface, accessible via LAN browser.
- Built with SvelteKit 5 (runes).
- Served by Ktor server from Android assets.
- Protected by HTTP Basic Authentication.

### WebSocket Endpoint
- `/ws/progress` provides real-time torrent status updates.
- Bypasses HTTP Basic Auth (page-level auth is the gate).
- Used by WebUI JavaScript to receive live progress and alerts.

## Android UI

### MainActivity
- The Android app's main screen, showing torrent list and controls.
- Provides gear icon to open password settings sheet.
- Creates `DefaultAuthManager` and passes it to `TorrentServer.start()`.

### Password Settings Sheet
- Material 3 `ModalBottomSheet` opened from MainActivity toolbar.
- Shows masked current password, new password input, change button.
- Calls `AuthManager.setPassword()` directly (equivalent to API endpoint).

## LAN (Local Area Network)

### Threat Model
- The app runs on a private LAN behind a router NAT.
- No public Internet exposure by default.
- HTTP Basic Auth credentials are base64-encoded (not encrypted).
- Acceptable for household privacy, not suitable for public Internet.

### Network Boundaries
- WebUI binds to `0.0.0.0:8080` (all interfaces).
- Accessible from any device on the same LAN.
- VPN split tunneling is external deployment configuration (not app logic).

### Emulator Acceptance
- A partial validation run on an Android Virtual Device (AVD).
- Covers Android UI and emulator-local WebUI behavior, but does not establish reachability from another LAN device.

### Physical-Device LAN Acceptance
- Validation on a physical Android device from a separate LAN browser.
- The required acceptance gate for the WebUI's LAN-accessibility claim.

## Storage

### Save Path
- The directory where torrent files are downloaded.
- In Stage 1: app-private external storage (`/storage/emulated/0/Android/data/...`).
- In Stage 4+: user-selected via SAF, represented as named logical destinations.

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
- **SAF permissions:** Stage 4 feature, represented as named destinations in WebUI. See ADR-004.
- **Session persistence:** Stage 3 feature, not implemented in Stage 1-2. See ADR-008.
- **HTTPS/TLS:** Milestone 5+ concern, not implemented in Stage 2.
