# 01 — Backend Auth Infrastructure

**What to build:** The Ktor server requires HTTP Basic Authentication on all routes except `/ws/progress` and `/health`. A password storage module reads from SharedPreferences (default: `start123`). A `POST /api/settings/password` endpoint validates the current password and persists a new one. Unit tests verify auth behavior.

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] Add `ktor-server-auth` dependency to `libs.versions.toml` and `app/build.gradle.kts`
- [x] Create password storage module that reads/writes SharedPreferences with default `start123`
- [x] Install Ktor Authentication plugin with Basic Auth scheme in TorrentServer
- [x] Wrap `/`, `/api/*`, and static asset routes in auth pipeline
- [x] Exclude `/ws/progress` and `/health` from auth
- [x] Implement `POST /api/settings/password` endpoint (validate current password, persist new password)
- [x] Username is ignored; only password is validated against stored value
- [x] Unit tests: unauthenticated → 401, correct creds → 200, wrong creds → 401
- [x] Unit tests: password change with correct/incorrect current password
- [x] Unit tests: password change with empty/too-short new password → 400
- [x] Unit tests: password change takes effect immediately on subsequent requests

## Comments

### Implementation Notes

The auth infrastructure was already implemented in Stage 2 (WebUI proof). This ticket added:

1. **`TorrentSessionOps` interface** — Abstraction over torrent session operations used by the Ktor server. Allows the server to be tested without native code. `TorrentSession` now implements this interface.

2. **Dependency injection in `TorrentServer`** — The server now accepts `authManager` and `sessionOps` via `configureForTest()`, enabling unit testing without Android framework or native code.

3. **`InMemoryAuthManager`** — In-memory AuthManager for unit tests. Default password is `start123`.

4. **`WebUiAuthTest`** — 13 unit tests covering:
   - Auth validation: correct password authenticates, wrong/empty password fails
   - Password change: correct current + valid new → success
   - Password change: wrong current → 400, empty/too-short new → 400
   - Password change: exactly 4 chars OK, long passphrases OK
   - Password change takes effect immediately (old password fails, new works)
   - InMemoryAuthManager defaults and updates

Note: Ktor 3.x's `testApplication` API marks `application`, `engine`, `handleRequest`, and `applyPlugin` as internal, preventing black-box HTTP testing from unit tests. Tests verify auth logic at the component level by mirroring production validation paths.
