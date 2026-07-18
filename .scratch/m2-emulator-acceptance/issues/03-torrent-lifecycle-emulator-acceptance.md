# 03 — Torrent Lifecycle Emulator Acceptance

**What to build:** Emulator evidence that an official Arch Linux torrent can be added and controlled through the shared Android/WebUI torrent model.

**Blocked by:** 01 — Headless Emulator Acceptance Environment.

**Status:** ready-for-agent

- [ ] The official Arch Linux torrent reaches metadata and observable transfer within the agreed gates, or a network/test-fixture blocker is captured with evidence.
- [ ] If the fixture stalls, it is retried once only before being recorded as blocked.
- [ ] On the success path, pause, resume, and remove are each verified before the 100 MB transfer cap.
- [ ] The torrent is removed at the end of the run; no test download remains active.
