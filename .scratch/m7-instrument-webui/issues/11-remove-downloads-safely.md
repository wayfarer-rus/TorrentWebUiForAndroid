# 11 — Remove Downloads safely

**What to build:** Separate removing a Download from the list from deleting its files, so a household user can choose the intended outcome with precise confirmation and durable pending/error feedback.

**Blocked by:** 04 — Present the consumer Download queue.

**Status:** complete (automated WebUI/JVM/static validation; no physical-device validation performed)

- [x] **Remove from list** is available as a secondary action and is not labeled Delete.
- [x] Its confirmation names the Download and explicitly states that downloaded and partial files will remain.
- [x] Successful Remove from list removes the durable Download entry while retaining its files.
- [x] **Remove and delete files** is presented separately and visibly as destructive.
- [x] Destructive confirmation names the Download and explicitly mentions downloaded and partial files.
- [x] The destructive flow makes no undo or recovery promise.
- [x] Each removal waits for durable backend acknowledgement before the card disappears.
- [x] Only the affected removal action is disabled and its pending label is specific.
- [x] Failure keeps the card available, restores valid actions, and shows curated consumer guidance.
- [x] Keyboard focus moves predictably into confirmation and returns appropriately on cancel/failure.
- [x] Semantic browser tests verify retained files versus deleted files through observable backend outcomes, both confirmations, cancellation, pending behavior, failure, and focus.


**Validation (automated only; no physical-device validation):** `cd web && npm run check`, `cd web && npm run build`, `./gradlew :app:testDebugUnitTest --console=plain`, and `cd web && npm run test:e2e:m4:static` passed. `cd web && npx playwright test --config=playwright.m6.config.mjs` passed (40 tests), including the three semantic removal tests. The static test preview emitted expected route-mock `/ws/progress` 404 messages; no test failed. `git diff --check` passed.

**Review:** The installed `/code-review` workflow requires a non-empty committed fixed-range diff, while `685485a...HEAD` is empty and this task intentionally remains uncommitted. Equivalent manual Standards and Spec review of the Issue 11 implementation found no blocking findings. No physical-device or separate-LAN-browser validation was run.
