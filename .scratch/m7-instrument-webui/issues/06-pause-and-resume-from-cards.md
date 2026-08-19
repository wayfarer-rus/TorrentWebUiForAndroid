# 06 — Pause and Resume from Download cards

**What to build:** Let users Pause or Resume an applicable Download directly from its card, with durable acknowledgement and precise local pending/error behavior that does not block unrelated Downloads.

**Blocked by:** 04 — Present the consumer Download queue.

**Status:** complete

- [x] A running applicable Download exposes Pause as its one primary action.
- [x] A paused applicable Download exposes Resume as its one primary action.
- [x] Invoking an action disables only that affected action, not other Download cards.
- [x] The pending label identifies the transition, such as **Pausing…** or **Resuming…**.
- [x] The UI does not present the requested state as durable until the backend acknowledges it.
- [x] A pause-requested backend state remains visibly **Pausing** until the durable state changes.
- [x] Failure restores an operable action and presents a specific consumer-facing message.
- [x] Repeated activation cannot submit duplicate mutations while the action is pending.
- [x] Keyboard focus remains predictable across pending, success, and failure transitions.
- [x] Semantic browser tests cover Pause, Resume, delayed acknowledgement, failure, duplicate prevention, and unaffected neighboring cards.

**Validation (2026-08-18; automated browser/JVM/static only):** `cd web && npm run check`, `cd web && npm run build`, and `cd web && npx playwright test --config=playwright.m6.config.mjs` passed (23 browser tests). `./gradlew :app:testDebugUnitTest --console=plain` passed when run sequentially after the WebUI build. `cd web && npm run test:e2e:m4:static` passed (6 tests). No physical-device validation ran.

**Review (2026-08-18):** The repository `/code-review` skill requires a committed fixed-range diff, but `685485a...HEAD` is empty because this work is uncommitted. Equivalent manual Standards and Spec review of the Issue 06 uncommitted patch found no blocking findings.
