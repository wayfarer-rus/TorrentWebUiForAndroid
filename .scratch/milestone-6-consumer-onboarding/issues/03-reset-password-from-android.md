# 03 — Reset the Password from Android

**What to build:** Give a person with physical access to the Android app a small recovery action that restores WebUI access when the current Password is unknown, without turning Android into a duplicate password-settings surface.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Android offers a clearly named Password Reset action with confirmation.
- [ ] Reset restores the Password directly to `start123`.
- [ ] Android never asks for the current Password and never accepts a replacement Password.
- [ ] Cancelling confirmation leaves authentication unchanged.
- [ ] A confirmed reset takes effect on the next authentication check and invalidates old browser credentials.
- [ ] Password values and authorization headers are never logged or exposed in diagnostics.
- [ ] Android remains free of queue, torrent, destination-selection, and Consumer Onboarding controls.
- [ ] Authentication and Android UI tests cover cancellation, confirmation, immediate effect, and browser reauthentication requirements.
