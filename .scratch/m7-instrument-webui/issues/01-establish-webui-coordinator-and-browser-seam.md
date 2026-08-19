# 01 — Establish the M7 WebUI coordinator and browser seam

**What to build:** Refactor the authenticated production WebUI so one page coordinator owns backend requests, live snapshots, and shared screen state while presentation modules consume narrow state/action interfaces. Preserve all current user-visible onboarding and Download behavior, and establish one semantic browser seam through which later M7 slices can be verified.

**Blocked by:** None — can start immediately.

**Status:** complete

- [x] The authenticated production WebUI still chooses correctly between Consumer Onboarding, an onboarding error, and the normal Downloads experience.
- [x] One coordinator owns HTTP interactions, live-update connection lifecycle, and the authoritative Download snapshot.
- [x] Presentation modules do not open duplicate live connections or independently invent shared backend state.
- [x] Existing Add Download, Download Folder, Password, Pause/Resume, move, removal, and onboarding behavior remains externally unchanged.
- [x] An authenticated semantic browser smoke test exercises the production page through roles, labels, and visible outcomes rather than implementation details.
- [x] Existing backend/domain interfaces and sensitive-data rules remain unchanged.
- [x] No new UI framework, design system, global state library, or third-party dependency is introduced.
- [x] Relevant existing WebUI and onboarding checks pass without weakened assertions.

**Validation (automated browser/static only; no physical-device validation):** `npm run check`, `npm run build`, `npm run test:e2e:m6:onboarding` (10 Playwright tests, including the authenticated coordinator smoke), and `npm run test:e2e:m4:static` (6 tests) passed.
