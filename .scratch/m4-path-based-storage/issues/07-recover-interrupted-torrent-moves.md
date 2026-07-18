# 07 — Recover interrupted torrent moves

**What to build:** After process termination, reboot, storage loss, or permission revocation interrupts a move, the user sees a durable Move Interrupted state and can explicitly retry or cancel without automatic deletion.

**Blocked by:** 06 — Move one torrent safely between destinations.

**Status:** ready-for-agent

- [ ] A durable move journal preserves enough state to inspect both source and target after restart.
- [ ] Recovery preserves both copies and never silently completes, deletes, or guesses a result.
- [ ] Authenticated WebUI exposes explicit retry and cancel actions with clear recoverable status.
