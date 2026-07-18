# 08 — Automate emulator storage E2E acceptance

**What to build:** A repeatable emulator suite proves the approved storage behavior through the real APK, JNI/libtorrent session, Android permission state, and authenticated WebUI/API, with complete teardown.

**Blocked by:** 03 — Manage canonical destination paths from WebUI; 05 — Recover unavailable destinations and permission loss; 07 — Recover interrupted torrent moves.

**Status:** ready-for-agent

- [ ] Emulator tests prove canonical API paths match observed emulator directories and cover per-torrent selection, conflict verification, moves, and recovery.
- [ ] Permission-ready, deny, and revocation states are exercised through the real installed app.
- [ ] Every run removes test downloads, journals, queue data, fixtures, credentials, and daemon/server state.
