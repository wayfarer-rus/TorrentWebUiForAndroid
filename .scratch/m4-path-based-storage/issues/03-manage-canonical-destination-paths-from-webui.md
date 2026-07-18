# 03 — Manage canonical destination paths from WebUI

**What to build:** An authenticated user can inspect reported storage-volume roots, browse validated child directories or paste a real path, and reuse approved canonical paths in a destination catalog.

**Blocked by:** 02 — Expose Android storage-permission readiness.

**Status:** ready-for-agent

- [ ] Only existing writable canonical paths under approved shared/external storage roots enter the catalog.
- [ ] System, app-private, inaccessible, and symlink-escaping paths are rejected with a recoverable explanation.
- [ ] The WebUI displays real paths only: no labels, aliases, opaque IDs, URIs, or synthetic paths.
