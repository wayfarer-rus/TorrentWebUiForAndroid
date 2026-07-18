# WebUI Basic Authentication

## Problem Statement

The WebUI is accessible on the LAN without any credentials. Any device on the same network can view and control active torrents. A household guest's phone browsing the local network could accidentally discover and interact with downloads.

## Solution

Add HTTP Basic Authentication to the Ktor server, gated by a password stored in Android SharedPreferences. The default password is `start123`. The password can be changed from the WebUI settings page or the Android app settings. The WebSocket endpoint bypasses auth (page-level auth is the gate — if you can't load the page, you can't get the JS that opens the socket).

## User Stories

1. As a household user, I want the WebUI to require a password, so that guests on my WiFi cannot access my downloads
2. As a first-time user, I want to access the WebUI with a known default password, so that I don't need to open the Android app just to read the password
3. As a user, I want to change the password from the WebUI settings, so that I don't need to switch to the Android app for maintenance
4. As a user, I want to change the password from the Android app settings, so that I have a fallback if I lock myself out of the WebUI
5. As a user, I want my browser to remember the password during a browsing session, so that I'm not prompted on every page navigation
6. As a user, I expect existing browser tabs to re-prompt after I change the password, so that stale sessions don't persist with the old credentials
7. As a user, I want the password change to require the current password, so that accidental changes are prevented
8. As a user, I want a clear error message if I enter the wrong current password, so that I know what went wrong
9. As a user, I want the new password to take effect immediately, so that I don't need to restart the app
10. As a user, I want the torrent list and controls to work normally after authenticating, so that the password doesn't disrupt my workflow
11. As a user, I want the live progress updates (WebSocket) to work after authenticating, so that my progress stays real-time

## Implementation Decisions

**Modules modified:**
- Ktor server module — add Authentication plugin with Basic Auth scheme
- New password storage module — reads/writes to SharedPreferences, provides `getPassword()` and `setPassword(newPassword)`
- TorrentServer routing — wrap `/`, `/api/*`, and static asset routes in auth pipeline; exclude `/ws/progress` and `/health`
- WebUI (SvelteKit) — add settings section with password change form
- Android UI (Compose) — add settings screen with password change

**Password storage:**
- SharedPreferences with key `webui_password`
- Default value: `start123`
- Read on every auth check (negligible cost; not cached in memory)
- Written by password change endpoint and Android settings

**API contract — Password change:**
- `POST /api/settings/password` — body: `{"currentPassword": "...", "newPassword": "..."}`
- Returns 200 on success, 400 if current password is wrong or new password is empty
- Auth middleware already verified the caller's credentials; `currentPassword` is an additional safety check

**API contract — Auth:**
- All routes except `/ws/progress` and `/health` require Basic Auth
- The auth challenge returns `WWW-Authenticate: Basic realm="Torrent WebUI"` header — browser renders a native credential dialog
- Username field is ignored; only password is validated

**WebSocket bypass rationale:**
- `new WebSocket()` in browsers cannot set custom headers
- Putting credentials in the URL (`ws://user:pass@host/...`) exposes password in browser history
- Since the page itself is auth-protected, reaching the JS that opens the socket requires authentication
- The WebSocket endpoint is effectively gated by page auth

**Password validation:**
- New password must be non-empty, minimum 4 characters
- No maximum length (allow passphrases)
- No complexity requirements (threat model is household privacy, not enterprise)

**Android settings:**
- Gear icon in MainActivity Toolbar opens a settings sheet
- Shows current password (plain text), input for new password, confirm button
- Calls the same `POST /api/settings/password` endpoint

## Testing Decisions

**What makes a good test:** Test external behavior — HTTP responses, not internal state. Treat the Ktor server as a black box from the test perspective.

**Seam: The Ktor server's HTTP layer** — single test point covering all auth behavior.

**Tests:**
- Unauthenticated request to `/` → 401 with `WWW-Authenticate` header
- Correct password to `/` → 200 with page content
- Wrong password to `/` → 401
- Correct password to `/api/torrents` → 200 with list
- Unauthenticated to `/api/torrents` → 401
- Password change with correct current password → 200, subsequent requests use new password
- Password change with wrong current password → 400
- Password change with empty new password → 400
- WebSocket to `/ws/progress` → connects without credentials
- `/health` → responds without credentials

**Prior art:** The existing codebase has no automated tests yet (Stage 1 relied on manual device testing per TEST_REPORT.md). These would be the first unit tests — Ktor's `testApplication` engine allows in-process testing without a device.

## Out of Scope

- HTTPS/TLS (Milestone 5+ concern; LAN-only by default)
- Session management or token-based auth
- Rate limiting or brute-force protection (threat model A)
- Password reset mechanism (Android app is the fallback)
- Per-user accounts (single password, single device)
- Password strength enforcement or complexity rules
- Enforcing password change on first login

## Further Notes

- Ktor 3.x `ktor-server-auth` module provides the Basic Auth scheme out of the box
- The `ktor-server-auth` dependency needs to be added to `libs.versions.toml` and `build.gradle.kts`
- After password change, in-memory auth state is irrelevant because SharedPreferences is read fresh on each request
- The WebUI is SvelteKit 5 (runes) — settings section should use `$state`
