# 02 — Configure the WebUI Port from Android

**What to build:** Let the Android device owner see and change the WebUI Port locally while torrent transfers continue. A valid change must make the WebUI reachable on the new port; an invalid, unavailable, or unpersistable change must leave the previous server and port working.

**Blocked by:** 01 — Extract a swappable daemon-owned WebUI server controller.

**Status:** implemented

- [x] Android displays the configured and effective WebUI Port without displaying or discovering a LAN address.
- [x] The default is `8080`, and only values from `1024` through `65535` are accepted.
- [x] Applying a valid available port binds a candidate, persists the value, promotes it, and retires the old server.
- [x] Port switching never initializes, destroys, pauses, or restarts the native torrent session.
- [x] Invalid input, bind failure, and persistence failure preserve the previous running server and configured port.
- [x] Cold-start bind failure is reported on Android without silently replacing the configured port.
- [x] Existing clients are expected to reconnect on the new port after a successful switch.
- [x] Tests cover persistence, process restart, successful promotion, every rollback path, and transfer-session continuity.
