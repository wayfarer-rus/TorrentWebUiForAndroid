# Storage Model

## Milestone 4 Model

Milestone 4 uses a path-based storage model. Each torrent has a real, canonical, SSH-copyable filesystem save path rather than a global session path or generic SAF document URI. The complete contract is specified in [Milestone 4 — Path-Based Storage Model](milestone-4-storage-model.md).

## Permission Ownership

Android requests All Files Access during app startup. It is a prerequisite for daemon storage operations, but it is broad permission: the backend—not Android permission scope—confines the product to approved folders.

- If denied at startup, the daemon does not start and Android presents retry guidance.
- If revoked while running, affected torrents pause; the authenticated WebUI shows `storage_permission_required`; add and move actions are rejected.
- After restoration, the user explicitly resumes affected torrents.

## Approved Destinations

An Approved Destination is an existing writable directory with a canonical path under a backend-reported shared/external storage volume. The path itself is its only identity in persistence and the authenticated WebUI/API.

- No labels, aliases, opaque IDs, raw `content://` URIs, or synthetic paths.
- The WebUI lists reported volume roots, browses validated child directories, and accepts a pasted absolute path.
- The backend rejects system paths, app-private paths, inaccessible paths, and symlink escapes.
- Every verified selection is reusable and becomes the default for the next torrent.
- A destination cannot be removed while a torrent or move journal references it.

## Per-Torrent Save Paths and Moves

New torrents default to the latest selected destination but may select another Approved Destination. Target files are never overwritten; reuse requires libtorrent piece verification.

A move operates on one torrent only: pause it, persist a journal, copy/move and verify data, persist the new path, then remove the source. Failure, cancellation, reboot, storage loss, or permission revocation preserves both copies and leaves the torrent in `move_interrupted` until explicit retry or cancel.

## Legacy Data

Existing app-private M3 downloads retain their actual paths as Legacy Destinations. They can continue or be moved one torrent at a time, but new torrents cannot select them.

## Path Visibility

Canonical paths may appear in authenticated WebUI/API responses and explicit user-requested diagnostics. They must not appear in routine Android logs, notifications, broadcasts, or generic errors.
