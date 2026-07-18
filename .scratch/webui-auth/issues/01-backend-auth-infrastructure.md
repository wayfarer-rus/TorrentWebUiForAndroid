# 01 — Backend Auth Infrastructure

**What to build:** The Ktor server requires HTTP Basic Authentication on all routes except `/ws/progress` and `/health`. A password storage module reads from SharedPreferences (default: `start123`). A `POST /api/settings/password` endpoint validates the current password and persists a new one. Unit tests verify auth behavior.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Add `ktor-server-auth` dependency to `libs.versions.toml` and `app/build.gradle.kts`
- [ ] Create password storage module that reads/writes SharedPreferences with default `start123`
- [ ] Install Ktor Authentication plugin with Basic Auth scheme in TorrentServer
- [ ] Wrap `/`, `/api/*`, and static asset routes in auth pipeline
- [ ] Exclude `/ws/progress` and `/health` from auth
- [ ] Implement `POST /api/settings/password` endpoint (validate current password, persist new password)
- [ ] Username is ignored; only password is validated against stored value
- [ ] Unit tests: unauthenticated → 401, correct creds → 200, wrong creds → 401
- [ ] Unit tests: password change with correct/incorrect current password
- [ ] Unit tests: password change with empty/too-short new password → 400
- [ ] Unit tests: password change takes effect immediately on subsequent requests
