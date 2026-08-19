# 08 — Open responsive Settings for Password and About

**What to build:** Present the existing household settings in the same responsive secondary-surface model as Details, limited to Password, Download folders navigation, and minimal About information.

**Blocked by:** 02 — Ship the Instrument application shell.

**Status:** complete (automated WebUI and JVM validation; no physical-device validation performed)

- [x] Settings opens as a right-side drawer on wide screens and a full-screen surface on phones.
- [x] Settings contains top-level entries for **Download folders**, **Password**, and minimal **About** only.
- [x] No advanced torrent, tracker, bandwidth, queue, network, theme, notification, or Android-owned settings are introduced.
- [x] Existing Password validation, submission, and browser reauthentication behavior remain intact.
- [x] Password values are never placed in URLs, history state, logs, or About information.
- [x] About uses product-neutral, device-agnostic wording and keeps engine terminology secondary.
- [x] Focus enters Settings on open and returns to the opener on close.
- [x] Escape, explicit close, and Browser Back close Settings correctly.
- [x] Reload returns to the home screen.
- [x] Semantic browser tests cover both responsive forms, Password success/failure, reauthentication, About content, focus, Back/Escape, and history privacy.

**Validation:** `cd web && npm run check`, `cd web && npm run build`, `cd web && npx playwright test e2e/m7-settings.spec.mjs --config=playwright.m6.config.mjs` (3 passed), `cd web && npm run test:e2e:m6:onboarding` (29 passed), and `./gradlew :app:testDebugUnitTest --console=plain` passed. Fixture-only `/ws/progress` 404 messages occurred during browser tests and did not fail them. No physical-device or separate-LAN-browser validation ran.
