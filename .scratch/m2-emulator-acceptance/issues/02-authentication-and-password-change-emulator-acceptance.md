# 02 — Authentication and Password-Change Emulator Acceptance

**What to build:** End-to-end emulator evidence that Android UI and WebUI protect access with the same password and can safely change it.

**Blocked by:** 01 — Headless Emulator Acceptance Environment.

**Status:** ready-for-agent

- [ ] Unauthenticated browser access is rejected and valid credentials are accepted.
- [ ] Android password settings open, require confirmation of the current password, and show feedback for a successful change.
- [ ] The actual WebUI settings form changes the password and rejects the prior password afterward.
- [ ] An ephemeral test password is never written to reports or logs, and the documented default password is restored before teardown.
- [ ] Android and WebUI evidence includes screenshots or equivalent UI-level artifacts.
