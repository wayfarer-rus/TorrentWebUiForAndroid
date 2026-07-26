# 03 — Reset the Password from Android

**What to build:** Give a person with physical access to the Android app a small recovery action that restores WebUI access when the current Password is unknown, without turning Android into a duplicate password-settings surface.

**Blocked by:** None — can start immediately.

**Status:** implemented

- [x] Android offers a clearly named Password Reset action with confirmation.
- [x] Reset restores the Password directly to `start123`.
- [x] Android never asks for the current Password and never accepts a replacement Password.
- [x] Cancelling confirmation leaves authentication unchanged.
- [x] A confirmed reset takes effect on the next authentication check and invalidates old browser credentials.
- [x] Password values and authorization headers are never logged or exposed in diagnostics.
- [x] Android remains free of queue, torrent, destination-selection, and Consumer Onboarding controls.
- [x] Authentication and Android UI-state tests cover cancellation, confirmation, immediate effect, and browser reauthentication requirements.
