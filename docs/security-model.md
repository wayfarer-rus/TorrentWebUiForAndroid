# Security Model

## Core Principles

- **LAN-only management.** The WebUI binds to LAN interfaces only. No public-facing ports.
- **Authenticated WebUI.** Password authentication is required by default. No anonymous access.
- **No public exposure by default.** The app does not offer port forwarding, UPnP, or NAT-PMP features.

## Threat Model

| Threat | Mitigation |
|--------|-----------|
| Accidental LAN access by unauthorized device | Password authentication; LAN-only binding |
| Weak password | Password must meet minimum complexity during setup |
| USB permission loss | Treated as normal recoverable state; no data loss |
| VPN misconfiguration | App does not control VPN; documented deployment guidance |
| App crash / process death | Foreground service; queue persistence |
| Credential logging | Credentials, tokens, magnets, tracker URLs never logged |

## VPN Split Tunneling

VPN split tunneling is **outside the app's control**. The app does not:
- Inspect another VPN app's configuration.
- Verify VPN routing rules.
- Attempt to modify VPN settings.
- Depend on a specific VPN provider.

Users configure split tunneling externally so that:
- Torrent traffic flows through the VPN.
- LAN WebUI traffic remains local and reachable.

Deployment documentation will cover recommended configurations.

## Data Handling

- Magnet URIs and tracker URLs are not logged.
- Downloaded files are stored only in user-approved locations.
- No telemetry or analytics by default.
- No cloud dependency; all data stays on-device.
