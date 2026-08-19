# 13 — Lock down M7 accessibility and responsive acceptance

**What to build:** Verify and close cross-slice accessibility, responsive, history, and consumer-language gaps through the production authenticated WebUI seam, without coupling tests to presentation internals or pixel-perfect screenshots.

**Blocked by:** 03 — Polish Add Download; 05 — Expose canonical Download Folder paths; 06 — Pause and Resume from Download cards; 07 — Open responsive Download Details; 08 — Open responsive Settings for Password and About; 09 — Manage Download Folders in Settings; 10 — Move files between Download Folders; 11 — Remove Downloads safely; 12 — Complete Needs Attention and reconnect recovery.

**Status:** complete (automated browser validation only)

- [x] Semantic Playwright acceptance covers the complete M7 home, Details, Settings, folder, move, recovery, and removal journeys.
- [x] Tests use roles, labels, headings, status/error associations, progress semantics, focus, history, and visible outcomes rather than CSS classes or module structure.
- [x] The 360×800, 768×1024, and 1440×900 matrices have no horizontal page scroll and keep all actions reachable.
- [x] Drawer/full-screen behavior, long names, long canonical paths, and every approved empty state pass at representative viewports.
- [x] All controls are keyboard-operable with logical Tab/Shift+Tab order and visible focus.
- [x] Details, Settings, confirmations, and nested folder browsing satisfy focus entry and restoration rules.
- [x] Escape and Browser Back close the correct temporary surface; reload returns home.
- [x] Browser URLs/history contain no Download names, canonical paths, runtime IDs, credentials, or settings values.
- [x] Semantic headings, labels, buttons, progress, and associated error/status messages are exposed correctly.
- [x] Reduced-motion preferences are respected and state is never conveyed by color alone.
- [x] Primary mobile targets are at least 44×44 CSS pixels.
- [x] Text and status accents meet WCAG 2.2 AA contrast fundamentals.
- [x] Prohibited torrent jargon is absent from the default home screen.
- [x] Screenshot comparison may support review but is not used as the acceptance oracle.

## Validation evidence

- `cd web && npm run check` — passed (0 diagnostics).
- `cd web && npm run build` — passed.
- `cd web && npx playwright test --config=playwright.m6.config.mjs --reporter=line` — passed (45 tests).
- `git diff --check` — passed.

No emulator, physical Android device, or separate-LAN-browser validation ran. The Playwright suite exercises mocked authenticated backend routes through the production WebUI seam; live Android/WebSocket rendering remains delivery-gate work.

**Repair validation (current working tree):** The semantic accessibility suite now exercises Browser Back for move confirmation, Remove from list confirmation, and Remove and delete files confirmation. `cd web && npx playwright test e2e/m7-accessibility-responsive-acceptance.spec.mjs --config=playwright.m6.config.mjs` passed (2 tests); the complete M6/M7 semantic suite then passed (46 tests).
