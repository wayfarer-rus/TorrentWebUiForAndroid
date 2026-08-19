# Security Model

## Core Principles

- **LAN-intended management.** The WebUI listens on the configured device port so trusted-LAN browsers can connect. The app does not create public reachability; network exposure remains the operator's responsibility.
- **Authenticated WebUI.** Password authentication is required by default. No anonymous access. The publicly known bootstrap credential should be replaced during onboarding.
- **No public exposure features.** The app does not offer router port forwarding, UPnP, or NAT-PMP configuration.

## Threat Model

| Threat | Mitigation |
|--------|-----------|
| Accidental access by an unauthorized network peer | Password authentication; operator keeps the device port reachable only from a trusted LAN. The server currently listens on all IPv4 interfaces and does not itself distinguish LAN, VPN, or public routes. |
| Publicly known bootstrap or weak password | Onboarding offers an immediate password change; documentation tells users not to retain the bootstrap credential on a shared LAN. Current minimum length is four characters and is not claimed as a strong-password guarantee. |
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

Deployment guidance is limited to the product boundaries in [SECURITY.md](../SECURITY.md) and [BUILD_AND_RUN.md](../BUILD_AND_RUN.md); the app does not validate external VPN routing.

## Data Handling

- Magnet URIs and tracker URLs are not logged.
- Downloaded files are stored only in user-approved locations.
- No telemetry or analytics by default.
- No cloud dependency; all data stays on-device.
