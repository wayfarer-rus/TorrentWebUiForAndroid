# 08 — Automate the clean-install onboarding happy path

**What to build:** Prove one bounded end-to-end Consumer Onboarding journey through the real APK, visible Android permissions, daemon, JNI/libtorrent session, Ktor server, packaged WebUI, HTTP Basic Authentication, and shared-storage filesystem.

**Blocked by:** 05 — Complete the recommended onboarding path.

**Status:** ready-for-agent

- [ ] The scenario starts from cleared application data without shell-granting Android permissions.
- [ ] The browser authenticates, observes Onboarding Readiness, confirms the backend-derived Recommended Destination, chooses **Set it later**, and reaches the normal WebUI.
- [ ] Timing begins at the first actionable Android Startup Bootstrap screen and ends at the authenticated normal WebUI.
- [ ] Normal setup completes in under three minutes, excluding tool installation and fixture provisioning.
- [ ] Rendered onboarding and recurring post-onboarding content never contain `start123`.
- [ ] The approved path exactly matches the ADB-observed canonical filesystem path.
- [ ] Completion survives browser refresh, daemon restart, and reopening MainActivity.
- [ ] Teardown stops the daemon/server and removes test data, durable queue/onboarding state, recovery records, credentials, fixtures, virtual storage, and forwarding.
- [ ] The scenario records emulator-only evidence and makes no physical-device LAN claim.
