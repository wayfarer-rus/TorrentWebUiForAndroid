# 12 — Complete Needs Attention and reconnect recovery

**What to build:** Turn every exceptional Download condition into a stable, actionable household recovery experience while preserving the last known queue during temporary live-update disconnection.

**Blocked by:** 06 — Pause and Resume from Download cards; 09 — Manage Download Folders in Settings; 10 — Move files between Download Folders; 11 — Remove Downloads safely.

**Status:** complete (automated validation only)

- [x] Each known recoverable condition presents curated consumer copy and a concrete next action.
- [x] Unavailable Download Folder guidance leads to the valid storage/folder recovery workflow without exposing internal diagnostics.
- [x] Interrupted and conflicting moves present the recovery actions established by the move slice.
- [x] Native errors and unknown states render as **Needs attention** without exposing arbitrary backend error strings.
- [x] The one primary action becomes **Fix problem**, **Retry move**, or **Cancel move** when appropriate.
- [x] Conditions without a safe automated action explain the smallest user action or Android recovery step.
- [x] A live-update disconnect preserves the last rendered cards as stale rather than clearing the queue.
- [x] Reconnecting status uses consumer wording such as **Updates paused — reconnecting…** and never says WebSocket on the default screen.
- [x] Successful reconnection replaces stale state with the next authenticated snapshot without duplicating Downloads.
- [x] One failing Download does not disable actions on unrelated Downloads.
- [x] Sensitive backend details, credentials, tracker information, magnet URIs, and routine destination-path diagnostics are not exposed.
- [x] Semantic browser tests cover every known exceptional mapping, unknown errors, recovery routing, disconnect, stale preservation, and reconnection.

## Validation

- `cd web && npm run check` — passed (0 diagnostics).
- `cd web && npm run build` — passed.
- `cd web && npx playwright test --config=playwright.m6.config.mjs` — passed (43 tests), including `m7-needs-attention-recovery.spec.mjs` (3 tests).
- `./gradlew :app:testDebugUnitTest --console=plain` — passed.
- `cd web && npm run test:e2e:m4:static` — passed (6 tests).
- `git diff --check` — passed.

No emulator, physical-device, USB-remount, or separate-LAN-browser validation ran. The browser suite must not run concurrently with Gradle because `copyWebAssets` replaces Vite output; an initial concurrent run was invalidated by that build-artifact race, then the serial rerun passed.
