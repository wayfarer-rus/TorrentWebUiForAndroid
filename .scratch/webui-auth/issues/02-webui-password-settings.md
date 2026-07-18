# 02 — WebUI Password Settings

**What to build:** A settings section in the WebUI header with a password change form. The form has fields for current password, new password, and a confirm button. Submitting validates against the API and shows success or error feedback.

**Blocked by:** 01 — Backend Auth Infrastructure

**Status:** ready-for-agent

- [ ] Settings gear icon or link in WebUI header
- [ ] Settings panel/section with password change form
- [ ] Form fields: current password, new password
- [ ] Submit calls `POST /api/settings/password` with Basic Auth header
- [ ] Success message displayed on 200 response
- [ ] Error message displayed on 400 response (wrong current password, empty new password)
- [ ] New password takes effect immediately — browser re-prompts on next navigation
- [ ] Form uses Svelte 5 runes (`$state`)
