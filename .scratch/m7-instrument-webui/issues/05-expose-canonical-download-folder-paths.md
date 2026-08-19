# 05 — Expose canonical Download Folder paths

**What to build:** Let a user verify and reuse the real filesystem location of every Download from its card, while keeping long canonical paths compact and making copying work on ordinary LAN HTTP.

**Blocked by:** 04 — Present the consumer Download queue.

**Status:** complete

- [x] Every Download card with a durable destination presents its backend-verified canonical Download Folder path; a legacy/missing durable destination shows recovery guidance and never substitutes a native path.
- [x] The canonical path remains the sole identity; no aliases, friendly names, synthetic paths, or document URIs are introduced.
- [x] Long paths may be visually truncated but the full canonical value remains present and selectable.
- [x] Copy path uses clipboard access when the browser permits it.
- [x] When clipboard access is unavailable or rejected, the complete path is selected and the user is instructed to invoke system copy.
- [x] A user reaches either a copied value or a complete selected value within two interactions.
- [x] The fallback works on plain LAN HTTP and does not claim that copying succeeded when it did not.
- [x] Keyboard and touch users can operate the path action.
- [x] Canonical paths are not placed in URLs, browser history state, routine logs, or unauthenticated content.
- [x] Browser tests cover clipboard success, clipboard rejection/unavailability, long paths, selection completeness, and the two-interaction limit.

**Validation (2026-08-18):** `cd web && npm run check`, `cd web && npm run build`, and `cd web && npx playwright test --config=playwright.m6.config.mjs` passed (20 browser tests). `./gradlew :app:testDebugUnitTest --tests com.andreiefimov.torrentwebui.OnboardingApiTest --console=plain` passed. Browser coverage verifies clipboard success, rejection, unavailable clipboard fallback, long-path truncation with complete selection, keyboard/touch operation, one-interaction copy/selection, and URL/history privacy. No physical-device validation was run.

**Repair validation (current working tree):** `cd web && npx playwright test e2e/m7-canonical-download-folder-paths.spec.mjs --config=playwright.m6.config.mjs` passed (4 tests), including a snapshot with no `destinationPath` and a divergent native `savePath`; the UI shows recovery guidance and never exposes the native value. `./gradlew :app:testDebugUnitTest --tests com.andreiefimov.torrentwebui.WebSocketSnapshotTest --console=plain` passed as part of the focused JVM run, proving that server snapshot construction does not promote a native `savePath` to `destinationPath`. The complete M6/M7 semantic suite then passed (46 tests).

**Review (2026-08-18):** The repository `/code-review` skill requires a committed fixed-range diff; this worktree has no post-`685485a` commits, so an equivalent manual Standards/Spec review of the uncommitted Issue 05 changes found no blocking findings.
