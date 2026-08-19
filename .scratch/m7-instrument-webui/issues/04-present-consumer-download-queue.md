# 04 — Present the consumer Download queue

**What to build:** Present every backend Download through stable household language and a calm queue hierarchy, with exceptional items promoted to **Needs Attention**, unfinished items under **Active**, and complete items under **Completed**.

**Blocked by:** 02 — Ship the Instrument application shell.

**Status:** complete

- [x] **Needs Attention** appears only when at least one Active Download requires action.
- [x] Unfinished Downloads appear under **Active**, including paused, recovering, moving, and failed Downloads.
- [x] Fully downloaded items appear under **Completed**, including items that are still Sharing.
- [x] Backend order is preserved within each section; runtime IDs are not treated as chronology.
- [x] Metadata, checking, allocation, and restore-check states render as **Preparing**.
- [x] Downloading, pause requested, paused, move, and finished/seeding states render as **Downloading**, **Pausing**, **Paused**, **Moving files**, and **Complete** respectively.
- [x] Unavailable storage, interrupted/conflicting move, native error, and unknown state render as **Needs attention**.
- [x] Each card shows the Download name, consumer state, and semantic progress.
- [x] Progress is exposed through native/ARIA progress semantics and understandable text.
- [x] State is not conveyed by color alone.
- [x] Long Download names remain readable and do not cause horizontal page overflow.
- [x] Transfer rates, peers, and other technical details are absent from the default card.
- [x] Semantic browser tests cover every state mapping, all empty states, section promotion, and backend ordering.

**Validation (2026-08-18):** `cd web && npm run check`, `cd web && npm run build`, and `cd web && npx playwright test --config=playwright.m6.config.mjs` passed (17 browser tests). No physical-device validation was run.

**Repair validation (current working tree):** `cd web && npx playwright test e2e/m7-consumer-download-queue.spec.mjs --config=playwright.m6.config.mjs` passed (2 tests). Its semantic mapping fixture covers every literal in the preparing, complete, attention, and active-move mappings, including finished, completed, and seeding snapshots with unavailable durable destinations: each remains Completed while showing recovery guidance. The complete M6/M7 semantic suite then passed (46 tests).
