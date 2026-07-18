# 03 — Android Settings Screen

**What to build:** A gear icon in the Android app toolbar opens a settings sheet with a password field and change button. The settings screen calls the same `/api/settings/password` endpoint to change the password.

**Blocked by:** 01 — Backend Auth Infrastructure

**Status:** resolved

- [x] Gear/settings icon in MainActivity Toolbar (alongside existing diagnostics icon)
- [x] Settings dialog/sheet with current password display
- [x] Input field for new password, confirm button
- [x] Calls `POST /api/settings/password` on confirm (via AuthManager directly)
- [x] Success/error feedback in Android UI
- [x] Minimal design — fits "Android is maintenance-only" principle
