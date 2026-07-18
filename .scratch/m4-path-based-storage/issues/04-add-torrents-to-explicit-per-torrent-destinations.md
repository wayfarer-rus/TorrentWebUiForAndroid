# 04 — Add torrents to explicit per-torrent destinations

**What to build:** When adding a torrent, the WebUI defaults to the latest selected canonical destination but lets the user choose another approved path; the torrent then persists and reports that path across native execution and recovery.

**Blocked by:** 01 — Expand per-torrent storage and import legacy paths; 03 — Manage canonical destination paths from WebUI.

**Status:** ready-for-agent

- [ ] A new torrent receives an explicit approved destination that is durable before success is reported.
- [ ] Queue, native execution, authenticated WebUI/API status, and recovery agree on the torrent’s canonical path.
- [ ] Existing target data is never overwritten and is reused only after piece verification.
