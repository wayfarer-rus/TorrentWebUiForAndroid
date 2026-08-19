# 10 — Move files between Download Folders

**What to build:** Let a user move one Download’s downloaded and partial files to another Approved Destination with explicit canonical-path confirmation and complete recovery for interrupted or conflicting moves.

**Blocked by:** 04 — Present the consumer Download queue; 09 — Manage Download Folders in Settings.

**Status:** complete (automated WebUI/JVM validation; no physical-device validation performed)

- [x] **Move files** is reachable as a secondary action from an applicable Download card.
- [x] Only another Approved Destination can be selected as the target Download Folder.
- [x] Confirmation shows the Download name and complete canonical source and target paths.
- [x] The move is not shown as accepted until the durable move record exists and the native engine acknowledges the request.
- [x] While moving, the card shows **Moving files** and only the affected actions are disabled.
- [x] Successful completion updates the Download Folder only after the established move guarantees complete.
- [x] Interrupted moves expose **Retry move** and **Cancel move** only when those actions are valid.
- [x] A non-empty/conflicting target is shown as **Needs attention** with actionable conflict guidance.
- [x] Failures and cancellation do not claim that source files were removed or the destination changed.
- [x] Existing files are never presented as overwritten.
- [x] Semantic browser tests cover confirmation, acknowledgement delay, completion, interruption, retry, cancellation, conflict, unavailable target storage, and unrelated-card operation.
- [x] Existing integrated move/recovery behavior remains unchanged and passes regression checks.

**Validation (automated only; no physical-device validation):** `cd web && npm run check`, `cd web && npm run build`, `./gradlew :app:testDebugUnitTest --console=plain`, `cd web && npm run test:e2e:m4:static`, and `cd web && npx playwright test --config=playwright.m6.config.mjs` passed (36 tests at the full-suite run; the focused move suite then passed 4 tests after adding its invalid-retry case). `git diff --check` passed. The earlier parallel Gradle/Playwright attempt was invalid because Gradle's `copyWebAssets` moved `web/build`; the sequential validation passed.

**Repair validation (current working tree):** The focused `OnboardingApiTest` JVM run passed. Its authenticated Ktor test proves unapproved move and retry targets return `409 Conflict` without invoking the move service, while approved targets reach the corresponding service operation. The complete M6/M7 semantic suite then passed (46 tests).
