# 07 — Open responsive Download Details

**What to build:** Give users an optional Details surface for technical and secondary information without cluttering the home card, using a right-side drawer on wide screens and a full-screen surface on phones.

**Blocked by:** 04 — Present the consumer Download queue.

**Status:** complete (automated WebUI validation; no physical-device validation performed)

- [x] **View details** is reachable from every applicable Download card.
- [x] Details opens as a right-side drawer on the approved wide viewport and as a full-screen surface on the approved phone viewport.
- [x] Details includes the Download name, complete canonical path, progress/state, transfer rates, peer information, and Sharing information when available.
- [x] Technical terminology is confined to Details and does not leak back onto the default card.
- [x] Focus moves into Details when it opens and returns to the opener when it closes.
- [x] Escape and an explicit close action close Details.
- [x] Browser Back closes Details before leaving the home screen.
- [x] Reload returns to the home screen rather than reopening Details.
- [x] URLs and history state contain no Download names, canonical paths, runtime IDs, or technical values.
- [x] Long names and paths remain usable without horizontal page overflow.
- [x] Semantic browser tests cover both responsive forms, focus entry/restoration, Escape, Browser Back, reload, and history privacy.

**Validation:** `cd web && npm run check`, `cd web && npm run build`, and `cd web && npx playwright test --config=playwright.m6.config.mjs` passed (26 tests). The expected fixture-only `/ws/progress` 404 messages do not represent a test failure. No physical-device or separate-LAN-browser validation ran.
