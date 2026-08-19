# 03 — Polish Add Download for household use

**What to build:** Make adding a Download from home a complete consumer flow: accept an existing supported magnet link under the label **Download link**, use an Approved Destination presented as a Download Folder, and give clear accessible success or correction feedback.

**Scope resolution:** The existing typed backend operation and `ARCHITECTURE.md` are magnet-only; torrent URLs are not a supported input and require separately designed backend support. This issue deliberately implements the documented magnet-only contract.

**Blocked by:** 02 — Ship the Instrument application shell.

**Status:** complete

- [x] Add Download is available directly from the home screen without opening Settings.
- [x] The input is labeled **Download link** and accepts the existing supported magnet-link input.
- [x] The destination control uses **Download Folder** consumer terminology and exposes only Approved Destinations.
- [x] A successful add waits for backend acknowledgement before reporting success.
- [x] After success, the Download link is cleared and the selected Download Folder is retained.
- [x] Success is visible and announced through an appropriate accessible status region.
- [x] Missing, invalid, rejected, and network-failed submissions show clear messages associated with the form.
- [x] A failed submission preserves the user’s Download link and selected Download Folder where safe.
- [x] Controls expose a specific pending state while the add request is in progress.
- [x] No credential, magnet URI, private tracker URL, or canonical path is routinely logged.
- [x] Semantic browser tests cover success, validation failure, backend failure, retained folder selection, cleared link, and accessible status.

**Validation (automated browser/static only; no physical-device validation):** `cd web && npm run check`, `cd web && npm run build`, and `cd web && npx playwright test --config=playwright.m6.config.mjs` passed (15 Playwright tests). The static preview server reported expected unhandled `/ws/progress` 404s while route-mocked browser tests ran.

**Repair validation (current working tree):** `cd web && npx playwright test e2e/m7-add-download.spec.mjs --config=playwright.m6.config.mjs` passed (3 tests), including rejection of `magnet:not-a-link` without a POST. The complete M6/M7 semantic suite then passed (46 tests).
