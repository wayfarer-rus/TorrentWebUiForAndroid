# Skill: WebUI

Operational guide for the browser-based WebUI.

## Primary Product UI

- The WebUI is the primary product interface.
- Responsive desktop/mobile layout is required.
- No torrent jargon on default screens.

## LAN-Only Assumptions

- The WebUI assumes LAN-only access.
- No features for public Internet exposure.
- No port forwarding, UPnP, or NAT-PMP.

## Authentication

- Password authentication required by default.
- Password set during onboarding in the Android app.
- No anonymous access mode.

## Shared Backend Model

- WebUI consumes the same domain/backend model as the Android UI.
- No separate storage logic or settings tree in the WebUI.
- Changes in either UI are reflected in the other.

## Filesystem Constraints

- The WebUI **must not browse raw Android filesystem paths**.
- Storage selection uses app-approved named destinations only.
- No fake filesystem browser or directory navigation.

## Future: WebSocket Live Updates

- Design the WebUI with WebSocket live updates in mind.
- Initial implementation may use HTTP polling.
- WebSocket is the target architecture for real-time progress.

## Default Screen Layout

- **Add Download** — paste magnet link or torrent URL.
- **Active** — currently downloading torrents with progress.
- **Completed** — finished downloads.
- Technical details hidden behind a details view/drawer.
