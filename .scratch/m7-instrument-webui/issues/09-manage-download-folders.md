# 09 — Manage Download Folders in Settings

**What to build:** Let a household administrator browse, approve, select, and forget Download Folders through backend-validated canonical filesystem paths, with recoverable handling for unavailable storage and clear protection for folders still referenced by Downloads.

**Blocked by:** 08 — Open responsive Settings for Password and About.

**Status:** complete (automated WebUI/JVM validation; no physical-device validation performed)

- [x] Download folders opens within Settings and replaces the Settings body rather than stacking a modal.
- [x] Back from folder browsing returns to the Download folders section.
- [x] Existing Approved Destinations are shown only by their complete canonical paths.
- [x] Browsing begins from mounted backend-reported Storage Volumes and follows only backend-returned readable children.
- [x] Pasted or browsed paths become selectable only after backend canonicalization, confinement, directory/writability validation, and durable approval.
- [x] Generic document URIs, aliases, synthetic paths, out-of-volume paths, and unavailable locations are rejected with consumer guidance.
- [x] USB disconnect/reconnect and revoked storage permission are presented as normal recoverable states.
- [x] A Download Folder referenced by a Download cannot be forgotten.
- [x] The blocked removal message explains that referencing Downloads must be moved or removed first.
- [x] Successful catalog changes update every consumer of the shared backend model without a duplicate settings source.
- [x] Canonical paths remain selectable and usable with keyboard and touch.
- [x] Semantic browser tests cover primary/removable browsing, canonicalization, rejection, unavailable storage, approval, successful removal, blocked referenced removal, and nested Back behavior.

**Validation (automated only; no physical-device validation):** `cd web && npm run check`, `cd web && npm run build`, `./gradlew :app:testDebugUnitTest --console=plain`, `cd web && npm run test:e2e:m4:static`, and `cd web && npx playwright test --config=playwright.m6.config.mjs` passed (33 tests). A prior parallel Playwright/Gradle run was invalid because Gradle rewrote `web/build` while Playwright served it; the subsequent sequential run passed.
