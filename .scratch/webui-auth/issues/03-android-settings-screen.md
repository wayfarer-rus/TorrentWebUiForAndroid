# 03 — Android Settings Screen

**What to build:** A gear icon in the Android app toolbar opens a settings sheet with a password field and change button. The settings screen calls the same `/api/settings/password` endpoint to change the password.

**Blocked by:** 01 — Backend Auth Infrastructure

**Status:** ready-for-agent

- [ ] Gear/settings icon in MainActivity Toolbar (alongside existing diagnostics icon)
- [ ] Settings dialog/sheet with current password display
- [ ] Input field for new password, confirm button
- [ ] Calls `POST /api/settings/password` on confirm
- [ ] Success/error feedback in Android UI
- [ ] Minimal design — fits "Android is maintenance-only" principle
