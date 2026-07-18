# 01 — Expand per-torrent storage and import legacy paths

**What to build:** A compatibility-preserving expansion from one global app-private save path to durable per-torrent storage intent, so every existing download remains usable as a Legacy Destination rather than being orphaned by the new model.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Existing queues retain their downloads and expose their actual legacy path without allowing new torrents to select it.
- [ ] Per-torrent path intent can coexist with the old behavior until dependent slices migrate to it.
- [ ] Focused tests prove atomic migration and queue recovery retain destination intent.
