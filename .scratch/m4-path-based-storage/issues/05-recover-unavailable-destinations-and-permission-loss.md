# 05 — Recover unavailable destinations and permission loss

**What to build:** A torrent whose per-torrent destination becomes unavailable or loses All Files Access pauses safely, stays visible with a recoverable storage state, and resumes only after restoration and explicit user action.

**Blocked by:** 02 — Expose Android storage-permission readiness; 04 — Add torrents to explicit per-torrent destinations.

**Status:** ready-for-agent

- [ ] Only torrents affected by an unavailable destination are paused.
- [ ] Startup revalidates referenced paths and retains recoverable state instead of guessing a replacement.
- [ ] Restored storage requires explicit resume; adds and moves remain rejected while permission is unavailable.
