# 02 — Ship the Instrument application shell

**What to build:** Replace the production WebUI chrome with the approved Instrument direction: a precise, compact, home-appliance-like dark interface headed **Downloads**, centered around a readable single-column experience that adapts cleanly from phone to desktop.

**Primary source:** The approved variant 2 prototype is preserved on branch `prototype/m7-instrument` at commit `5462b40`. Treat it as decision evidence, not production implementation.

**Blocked by:** 01 — Establish the M7 WebUI coordinator and browser seam.

**Status:** complete

- [x] The authenticated home screen title is **Downloads**.
- [x] The presentation uses the approved Instrument dark direction with teal status/action accents and restrained monospace status typography.
- [x] The main content is a centered readable single column rather than a dense grid.
- [x] The home hierarchy places Add Download first, followed by Download status sections.
- [x] Entirely empty state presents a clear first-Download call to action.
- [x] Completed-only and no-completed states use the approved compact/quiet empty treatments.
- [x] The layout has no horizontal page scroll at 360×800, 768×1024, or 1440×900.
- [x] Primary actions remain reachable at each representative viewport.
- [x] Default home chrome contains no prohibited torrent-engine jargon.
- [x] The throwaway prototype route, launcher, and static sample state are absent from production builds.

**Validation (automated browser/build only; no physical-device validation):**
- `cd web && npm run check`
- `cd web && npm run build`
- `cd web && npx playwright test --config=playwright.m6.config.mjs` — 12 passed, including M7 shell coverage for the three required viewports and empty-state variants.
- `cd web && npm run test:e2e:m4:static` — 6 passed.
- Production source/build search found no prototype route, launcher, or static sample markers; `git diff --check` passed.

**Review:** two-axis working-tree review found no blocking standards or issue-spec findings. The installed code-review skill's parallel sub-agent runner is not exposed by this harness, so the standards and spec axes were performed manually against the working-tree patch.
