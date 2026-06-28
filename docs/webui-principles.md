# WebUI Principles

## One Source of Truth for Settings

- Settings are defined once in the shared backend model.
- The Android app must not have a second, competing settings tree.
- Changes made in either UI surface are reflected in the other.

## Logical Destinations

- The browser UI uses named, logical destinations for storage.
- No raw Android filesystem paths are exposed.
- Adding or removing destinations is done through the Android app.

## Default Screen

The default WebUI screen shows:
- **Add Download** — paste a magnet link or torrent URL.
- **Active** — currently downloading torrents.
- **Completed** — finished downloads.

No torrent jargon on default screens. Technical details (peers, DHT, piece size, etc.) are hidden behind a details view/drawer.

## No Fake Filesystem Browser

- The WebUI does not pretend to browse the Android filesystem.
- Users cannot navigate arbitrary directories from the browser.
- Storage selection is limited to app-approved named destinations.

## Future: Live Updates

- The WebUI is designed for future WebSocket-based live updates.
- Initial implementation may use polling; WebSocket is the target architecture.

## Authentication

- Password authentication required by default.
- Password set during onboarding in the Android app.
- No anonymous access mode.

## LAN-Only Assumptions

- The WebUI assumes LAN-only access.
- No features for public Internet exposure.
- No port forwarding or UPnP.
