# Storage Model

## Overview

The app uses a two-phase storage model:

1. **Proof-of-concept phase:** App-private external storage only. Used during early development and testing.
2. **Production phase:** SAF (Storage Access Framework) with user-approved named locations.

## SAF Model (Production)

### Permission Ownership

- The Android app is responsible for acquiring folder permissions via SAF.
- The browser WebUI cannot invoke Android permission pickers directly.
- Permissions are persisted where possible (`takePersistableUriPermission`).

### Named Destinations

- Approved folders are presented as named, human-readable destinations.
- Examples: "Internal Storage / Downloads", "USB Drive / Media".
- Raw Android paths and document URIs are never exposed to users or the WebUI.

### WebUI Storage Selector

- The WebUI shows a list of approved named destinations.
- Users select from the list; they cannot browse arbitrary filesystem paths.
- Adding a new destination requires using the Android app's SAF picker.

### Recovery States

| Event | Behavior |
|-------|----------|
| USB disconnect | Destination marked unavailable; torrents paused; resumes on reconnect |
| USB reconnect | Destination revalidated; torrents resumed |
| Permission revoked | Destination removed from list; user re-authorizes via Android app |
| Storage full | Torrents paused; user notified via WebUI and notification |
| Device reboot | Persisted permissions revalidated; non-persisted permissions require re-authorization |

## Constraints

- The WebUI never sees raw Android paths.
- The WebUI never sees document URIs (`content://`).
- Storage operations are mediated entirely by the Android app.
- Multiple destinations may be supported; the first implementation targets one approved destination.
