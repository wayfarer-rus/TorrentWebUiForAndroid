# 02 — Expose Android storage-permission readiness

**What to build:** Android startup obtains and verifies All Files Access, while the authenticated WebUI accurately represents whether storage operations are ready or blocked without allowing unsafe torrent actions.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Permission denial at startup prevents daemon storage operations and presents native retry guidance.
- [ ] Authenticated WebUI represents ready and storage-permission-required states consistently with Android.
- [ ] Runtime permission loss blocks storage-changing actions and produces no routine path logging.
