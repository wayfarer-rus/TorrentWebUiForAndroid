# Architecture Decision Records

## ADR-001: Browser-First Product Model

- **Date:** 2026-06-28
- **Status:** Accepted
- **Context:** The product needs to serve a household user who will interact with it from multiple devices on the LAN. A native Android-only UI would limit accessibility and increase development complexity.
- **Decision:** The primary product interface is a LAN-accessible WebUI. The Android app serves as a bootstrap shell for permissions, onboarding, and service management.
- **Consequences:** WebUI receives primary UX investment. Android UI is intentionally minimal. Both consume the same backend model.

## ADR-002: Android App as Permission/Bootstrap Shell

- **Date:** 2026-06-28
- **Status:** Accepted
- **Context:** Android requires native components for SAF permissions, foreground services, and lifecycle management. A pure-web approach cannot acquire these permissions.
- **Decision:** The Android app handles permissions acquisition, service lifecycle, and onboarding. The WebUI handles day-to-day interaction.
- **Consequences:** Two UI surfaces must stay synchronized via a shared domain model. Settings duplication is avoided.

## ADR-003: Future Torrent Engine Behind JNI

- **Date:** 2026-06-28
- **Status:** Accepted
- **Context:** libtorrent-rasterbar is a C++ library. Direct embedding in Kotlin is not possible. JNI is the bridge.
- **Decision:** The native torrent engine runs behind a narrow JNI boundary. Kotlin never owns native objects directly. JNI exchanges small typed DTOs.
- **Consequences:** Clear ownership semantics. Native crashes are isolated. Kotlin side remains stable and testable.

## ADR-004: SAF Logical Destination Model

- **Date:** 2026-06-28
- **Status:** Superseded by [ADR 0015](docs/adr/0015-path-based-destinations-use-all-files-access.md) and [ADR 0023](docs/adr/0023-canonical-path-is-the-destination-identity.md)
- **Context:** Android's scoped storage model prevents arbitrary filesystem access. The WebUI runs in a browser and cannot invoke Android pickers directly.
- **Decision:** Superseded. Milestone 4 uses validated canonical filesystem paths with All Files Access, not generic SAF logical destinations.
- **Consequences:** The current storage contract is [Milestone 4 — Path-Based Storage Model](docs/milestone-4-storage-model.md).

## ADR-005: No VPN-Provider Coupling

- **Date:** 2026-06-28
- **Status:** Accepted
- **Context:** Users may deploy the app behind various VPN providers with different split-tunneling configurations. The app cannot reliably inspect or control another VPN app's behavior.
- **Decision:** VPN split tunneling is treated as external deployment configuration. The app provides no VPN provider integration, inspection, or control.
- **Consequences:** Deployment documentation must cover split tunneling. The app cannot guarantee VPN routing correctness internally.

## ADR-006: Consumer-First Defaults Over Expert Settings

- **Date:** 2026-06-28
- **Status:** Accepted
- **Context:** Target users are household users, not torrent enthusiasts. Exposing advanced torrent settings by default creates confusion and misconfiguration risk.
- **Decision:** Default UX hides advanced torrent internals. An advanced mode may be added later as an opt-in toggle.
- **Consequences:** Power users may need to enable advanced mode. Default screens show only essential controls and status.

## ADR-007: libtorrent-rasterbar v2.0.10 via Git Submodule

- **Date:** 2026-06-28
- **Status:** Accepted
- **Context:** Stage 1 requires embedding libtorrent-rasterbar in the Android app. The library needs to be version-pinned and reproducible.
- **Decision:** Use libtorrent-rasterbar v2.0.10 (commit 74bc93a37) as a git submodule. Boost 1.86.0 headers downloaded separately from archives.boost.io.
- **Consequences:** Build requires submodule initialization. Boost headers are not version-controlled but downloaded during build setup. License is Boost Software License 1.0 (BSD-style, permissive).

## ADR-008: No Session Persistence in Stage 1

- **Date:** 2026-06-28
- **Status:** Accepted
- **Context:** Stage 1 is a proof of concept. Implementing session persistence across process death requires foreground service, resume data serialization, and lifecycle management that would significantly increase complexity.
- **Decision:** Session is not persisted across app restart in Stage 1. This is documented as a known limitation.
- **Consequences:** Users lose active torrents on app kill. Stage 3 (Persistent Daemon) will address this.

## ADR-009: Polling-Based Status Updates

- **Date:** 2026-06-28
- **Status:** Accepted
- **Context:** libtorrent provides an alert mechanism for real-time updates, but integrating it with Android's main thread and Kotlin coroutines adds complexity. For Stage 1, polling is simpler and sufficient.
- **Decision:** Use 1-second polling via ViewModel coroutine. Alerts are consumed only for error reporting.
- **Consequences:** Slight latency in UI updates (up to 1s). Minimal CPU overhead. Can be replaced with alert-driven updates in future stages.
