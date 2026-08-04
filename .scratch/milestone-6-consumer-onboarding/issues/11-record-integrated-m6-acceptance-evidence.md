# 11 — Record integrated M6 acceptance evidence

**What to build:** Run the bounded M6 and existing regression suites on the final integrated tree, reconcile documentation with observed behavior, and produce reviewable evidence without reusing results from earlier revisions.

**Blocked by:** 09 — Automate onboarding interruption and credential recovery; 10 — Automate WebUI Port lifecycle acceptance.

**Status:** done

- [x] Focused JVM, API, Android UI, browser, and static WebUI checks pass on the final tree.
- [x] The clean-install, interruption/credential, and port-lifecycle emulator scenarios pass with verified teardown.
- [x] Existing M4 storage, authentication, WebSocket, and daemon-lifecycle regression suites pass on the final tree.
- [x] The repository test report records only commands actually run, environments, observed results, teardown evidence, and explicit residual gaps.
- [x] Architecture, roadmap, and decision documentation use the canonical Consumer Onboarding, Android Startup Bootstrap, Onboarding Readiness, WebUI Port, Password Reset, and Recommended Destination terms.
- [x] Milestone exit criteria are checked only where supported by executed evidence.
- [x] No new dependency is introduced without license and source documentation.
- [x] The repository-required Standards and Spec review axes both report zero findings before commit.
