# Roadmap

## Current Delivery Status

- **Current implementation workstream:** **Milestone 7 — Polished WebUI**.
- **Latest automated milestone evidence:** Milestone 6 Consumer Onboarding passes the required API 36 emulator acceptance recorded in [TEST_REPORT.md](TEST_REPORT.md); it is not yet a delivered milestone because physical-device/separate-LAN-browser validation has not run.
- **Last delivered milestone:** Milestone 3 — Persistent Daemon.
- **Outstanding validation:** Milestone 4 physical-device/Termux deployment validation is optional and has not been performed; Milestone 5 VPN-safe appliance validation remains open; and Milestone 6 Ticket 12 remains a required delivery gate, deferred while Milestone 7 implementation proceeds. Earlier Stage 1 manual gaps remain documented in [TEST_REPORT.md](TEST_REPORT.md) and [STAGE1_STATUS.md](STAGE1_STATUS.md); later emulator milestones do not retroactively prove them.

## Milestone 1: Native Engine Proof

**Objective:** Prove that our Android app can embed libtorrent-rasterbar and manage a real torrent session safely.

**Scope:**
- Bundle libtorrent-rasterbar through Android NDK/CMake.
- Minimal Kotlin-facing JNI bridge.
- One libtorrent session.
- User pastes one magnet URI.
- Download into app-private external storage only.
- Display: torrent name, state, progress, download rate, upload rate, peers, save location.
- Support: add, pause, resume, remove (and delete files).
- Diagnostics: ABI, libtorrent version, native load result, session startup result, last error.
- Validate using an Ubuntu torrent only.

**Out of scope:** WebUI, USB/SAF, VPN, RSS, categories, boot restore, polished UX.

**Exit criteria:**
- [ ] App builds and runs on arm64-v8a Android 13 device.
- [ ] Magnet added, metadata retrieved, files downloading.
- [ ] Real status displayed (name, state, progress, rates, peers).
- [ ] Pause, resume, remove work correctly.
- [ ] Diagnostics panel shows valid ABI, version, and session state.
- [ ] TEST_REPORT.md updated with real-device results.

---

## Milestone 2: Browser Control Proof

**Objective:** Prove LAN-accessible authenticated WebUI can control the torrent session.

**Scope:**
- LAN WebUI with password authentication.
- Add magnet via browser.
- List queue with progress.
- Pause, resume, remove from browser.
- Use same backend model as Android UI.

**Out of scope:** USB folder picker, raw filesystem browser, polished design.

**Exit criteria:**
- [x] WebUI accessible from LAN browser.
- [x] Password authentication enforced (HTTP Basic Auth, default password `start123`).
- [x] Magnet added, queue listed, progress visible.
- [x] Pause, resume, remove work from browser.
- [x] Android UI and WebUI show consistent state (shared backend model).

---

## Milestone 3: Persistent Daemon

**Specification:** [stage-3-persistent-daemon.md](prompts/stage-3-persistent-daemon.md)

**Objective:** Torrent engine survives app backgrounding and process death.

**Scope:**
- Foreground service with notification.
- Queue persistence across process death.
- Reboot recovery (later).

**Exit criteria:**
- [x] Torrent continues when app is backgrounded under the foreground daemon.
- [x] Durable queue intent is recovered after ordinary process termination/relaunch.
- [x] Notification exposes aggregate daemon state and safe stop.

---

## Milestone 4: Path-Based Storage Model

**Specification:** [milestone-4-storage-model.md](docs/milestone-4-storage-model.md)

**Objective:** Per-torrent download destinations represented by their real, canonical, SSH-copyable filesystem paths.

**Scope:**
- Android startup requests All Files Access; permission loss is recoverable.
- WebUI browser/paste selection of backend-validated shared/external storage paths.
- Reusable canonical-path catalog; latest selection defaults new torrents.
- Per-torrent save paths, explicit one-torrent moves, conflict verification, and durable move recovery.
- Legacy preservation of existing app-private downloads.

**Out of scope:** Generic SAF destinations, labels/aliases/opaque IDs, port and torrent-parameter settings, bulk moves, and automatic deletion after interrupted moves.

**Exit criteria:**
- [x] Android startup handles All Files Access grant/denial and runtime revocation safely.
- [x] WebUI exposes only canonical, backend-validated paths and never synthetic/URI destinations.
- [x] New torrents use an explicit per-torrent destination; existing target data is reused only after libtorrent verification and is never blindly overwritten.
- [x] A one-torrent move is recoverable after failure, cancellation, or process termination.
- [x] Legacy destinations remain represented by their real path and can be retained or moved safely.
- [x] Required emulator E2E validates real APK/JNI, permissions, paths, WebUI/API, moves, recovery, and cleanup.
- [ ] Optional physical-device Termux/SSH deployment check (not required for automated M4 acceptance).

---

## Milestone 5: VPN-Safe Appliance Validation

**Objective:** Validate the app works correctly when deployed behind a VPN with split tunneling.

**Scope:**
- Deployment documentation for VPN split tunneling.
- LAN WebUI remains reachable while torrent traffic is VPN-routed.
- VPN drop validation (torrents pause/resume gracefully).

**Out of scope:** App cannot verify or control another VPN provider internally.

**Exit criteria:**
- [ ] Deployment guide documents split tunneling configuration.
- [ ] WebUI accessible on LAN while torrent uses VPN.
- [ ] VPN disconnect handled without crash.

---

## Milestone 6: Consumer Onboarding

**Status:** Automated emulator acceptance complete; required physical-LAN delivery validation is deferred.

**Objective:** First-time user can set up the app without technical knowledge.

**Scope:**
- Android Startup Bootstrap acquires platform permissions and starts the service without duplicating the browser journey.
- Consumer Onboarding presents consumer-only Onboarding Readiness, a Recommended Destination or alternate Approved Destination, and an optional Password choice.
- WebUI Port configuration and local Password Reset remain Android fallback controls.
- Safe defaults apply automatically; technical torrent settings stay outside the journey.

**Exit criteria:**
- [ ] New user completes setup in under 3 minutes — API 36 emulator evidence is 56.137 seconds; physical-device confirmation is pending.
- [ ] No technical settings required — the emulator journey required only permission, destination, and Password decisions; physical-device confirmation is pending.
- [ ] WebUI accessible after setup — authenticated Chromium reached normal controls through owned ADB forwarding and retained completion after refresh and daemon restart; separate-LAN-browser confirmation is pending.

Ticket 12 is the remaining M6 delivery gate. Its physical-device and separate-LAN-browser validation is deferred while Milestone 7 implementation proceeds; M6 must not be described as delivered, and physical-device or real-LAN behavior must not be described as verified, until that ticket passes.

---

## Milestone 7: Polished WebUI

**Objective:** Modern, responsive WebUI suitable for daily use.

**Scope:**
- Responsive desktop/mobile layout.
- Queue cards with progress.
- Details drawer for torrent info.
- Clean empty states.
- Categories only if demonstrably useful.

**Exit criteria:**
- [ ] WebUI usable on desktop and mobile browsers.
- [ ] Queue, progress, and controls are intuitive.
- [ ] No torrent jargon on default screens.

---

## Milestone 8: Media-Server Workflow

**Objective:** Integration-friendly file organization for media servers.

**Scope:**
- Intake and completed folder organization.
- Optional logical destinations.
- Jellyfin-friendly naming/structure.

**Exit criteria:**
- [ ] Completed files organized into configured folders.
- [ ] Media server can discover new content.

---

## Milestone 9: Reliability Hardening

**Objective:** App handles real-world failure modes gracefully.

**Scope:**
- USB disconnect/reconnect.
- Storage full.
- Corrupted resume state.
- Permission revocation.
- Network transition (WiFi ↔ mobile).
- Restart recovery.

**Exit criteria:**
- [ ] Each failure mode tested on device.
- [ ] App recovers without data loss or crash.
- [ ] TEST_REPORT.md documents all scenarios.

---

## Milestone 10: Optional Extras

**Objective:** Quality-of-life features for power users.

**Scope:**
- RSS feeds.
- Download schedules.
- Push notifications.
- Per-torrent rules.
- Advanced mode toggle.

**Exit criteria:** TBD per feature.
