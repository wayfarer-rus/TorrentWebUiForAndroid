# 06 — Move one torrent safely between destinations

**What to build:** An authenticated WebUI user can explicitly move one torrent to another Approved Destination without silently changing other torrents or risking target/source data loss.

**Blocked by:** 04 — Add torrents to explicit per-torrent destinations; 05 — Recover unavailable destinations and permission loss.

**Status:** ready-for-agent

- [ ] Only the selected torrent pauses and reports moving progress while other torrents continue normally.
- [ ] Target data is copied/moved and verified before its destination changes; the source is removed only after durable success.
- [ ] Conflicts, cancellation, and failures retain source data and leave a recoverable paused state.
