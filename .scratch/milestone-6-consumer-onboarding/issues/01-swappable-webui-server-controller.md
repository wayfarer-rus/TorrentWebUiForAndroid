# 01 — Extract a swappable daemon-owned WebUI server controller

**What to build:** Preserve the current LAN WebUI behavior while giving the Torrent Daemon one narrow lifecycle operation that can start a candidate server, promote it, roll it back, and stop it without changing native torrent-session ownership. This prefactor makes safe WebUI Port changes possible without broadening Android or WebUI product behavior.

**Blocked by:** None — can start immediately.

**Status:** implemented

- [x] The Torrent Daemon remains the sole owner of the WebUI server lifecycle.
- [x] Existing startup, permission-blocked, safe-stop, and shutdown behavior remains externally unchanged on port `8080`.
- [x] A candidate server can bind without first stopping the active server.
- [x] A candidate can be promoted or discarded through one narrow typed lifecycle seam.
- [x] Candidate failure leaves the active server and native torrent session unchanged.
- [x] Focused tests cover start, candidate bind, promotion, rollback, and idempotent shutdown.
- [x] Existing daemon and WebUI lifecycle regressions pass.
