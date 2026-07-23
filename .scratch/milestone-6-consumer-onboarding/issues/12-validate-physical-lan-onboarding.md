# 12 — Validate physical-device LAN onboarding

**What to build:** Validate the completed M6 experience on a physical Android device from a separate LAN browser so real network behavior, browser authentication, port switching, and recovery are not claimed from emulator forwarding alone.

**Blocked by:** 11 — Record integrated M6 acceptance evidence.

**Status:** ready-for-agent

- [ ] A physical Android device completes Android Startup Bootstrap without test-only permission shortcuts.
- [ ] A separate LAN browser authenticates and completes Consumer Onboarding using a real canonical Approved Destination.
- [ ] The WebUI remains reachable on the configured LAN port while MainActivity is backgrounded.
- [ ] A successful WebUI Port change requires reconnecting on the new port and does not interrupt torrent-session ownership.
- [ ] An unavailable port preserves the previous reachable server and reports the failure on Android.
- [ ] Password change and local Password Reset both produce the expected browser reauthentication behavior.
- [ ] No public exposure, router forwarding, VPN-app inspection, root, Termux, or private hostname assumption is introduced.
- [ ] Test data and settings are cleaned up, and the repository test report records the exact device, browser, commands/actions, observed results, and residual limitations.
