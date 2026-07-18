# 02 — WebUI Password Settings

**What to build:** A settings section in the WebUI header with a password change form. The form has fields for current password, new password, and a confirm button. Submitting validates against the API and shows success or error feedback.

**Blocked by:** 01 — Backend Auth Infrastructure

**Status:** resolved

- [x] Settings gear icon or link in WebUI header
- [x] Settings panel/section with password change form
- [x] Form fields: current password, new password
- [x] Submit calls `POST /api/settings/password` with Basic Auth header
- [x] Success message displayed on 200 response
- [x] Error message displayed on 400 response (wrong current password, empty new password)
- [x] New password takes effect immediately — browser re-prompts on next navigation
- [x] Form uses Svelte 5 runes (`$state`)
