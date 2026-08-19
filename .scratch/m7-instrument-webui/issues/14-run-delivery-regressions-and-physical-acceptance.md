# 14 — Run delivery regressions and physical acceptance

**What to build:** Prove that the Instrument WebUI is ready for daily household use without regressing authentication, Consumer Onboarding, storage, move recovery, daemon behavior, background operation, or Android/WebUI ownership, and record bounded physical evidence accurately.

**Blocked by:** 13 — Lock down M7 accessibility and responsive acceptance.

**Status:** blocked — current-tree automated browser, JVM, Android instrumentation, native/integration, and emulator-served live-WebUI checks pass; physical-device and separate-LAN acceptance remains unavailable.

- [x] Current-tree WebUI checking/building and the complete semantic M7 Playwright suite pass.
- [x] Existing M6 authenticated browser and Consumer Onboarding suites pass with selectors changed only for approved user-visible behavior.
- [x] Existing M4 authentication, live-snapshot, Approved Destination, Pause/Resume, move/recovery, removal, storage-unavailability, permission-revocation, and daemon-lifecycle regressions pass.
- [x] Relevant JVM, Android, native/integration, and emulator E2E suites pass for the final tree.
- [ ] A real APK is installed and exercised on a physical Android host.
- [ ] A browser on that Android host verifies the 360×800-class phone/touch experience and core Add Download/card/secondary-surface actions.
- [ ] A separate LAN desktop browser verifies authentication, reachability, background operation, live updates, and desktop drawer behavior.
- [x] Canonical paths observed through the authenticated WebUI remain real backend-verified filesystem paths.
- [x] LAN-only defaults, HTTP Basic Authentication, and unauthenticated content/API/live-update rejection remain intact.
- [x] Android remains limited to permission bootstrap, service health/recovery, WebUI Port, Password Reset, and fallback controls.
- [x] The final production build contains no throwaway prototype route or static canonical-looking sample state.
- [ ] Test evidence records the device, Android version, APK/tree identity, browsers, viewport sizes, network path, scenarios, and cleanup.
- [ ] M6 physical-ticket evidence and M7 delivery evidence are recorded as separate claims even if one bounded run supplies observations for both.
- [x] Emulator, ADB-forwarded, headless, compile, build, or prototype evidence is not described as physical-device or separate-LAN validation.
- [x] Architecture, roadmap, decision, notices, and test-report documentation is updated only where the verified implementation requires it.

## Validation evidence

- `cd web && npm run check && npm run build` passed with zero Svelte diagnostics. `cd web && npm run test:e2e:m6:onboarding` was rerun on the current repaired tree and passed 46 Playwright browser tests. `cd web && npm run test:e2e:m4:static` also passed 6 static M4 tests on the current repaired tree.
- M6 policy suites passed: clean-install 7/7, interruption 5/5, and WebUI Port 4/4.
- `./gradlew :app:testDebugUnitTest :app:lintDebug --console=plain` completed successfully; the unit-test task was up-to-date and lint passed.
- On the isolated visible `emulator_skill` AVD (Android 16/API 36, arm64-v8a), notification and All Files Access were granted through Android UI. The M3 failures were reproduced with focused instrumentation, then corrected in the acceptance harness: instrumentation process restarts are no longer treated as user force stops, daemon service destruction is awaited before restart, corrupt recovery now asserts the production `RecoveryBlocked` safety contract, and migrated queue entries assert their canonical destination. Focused M3 tests passed, the complete M3 class passed 18/18, and the final focused `M3EmulatorAcceptanceTest` + `M4EmulatorAcceptanceTest` + `WebUiPortLifecycleTest` run passed 63/63. The post-review `./gradlew :app:connectedDebugAndroidTest --console=plain` rerun completed with `BUILD SUCCESSFUL`; generated XML records 134 tests, zero failures/errors, and one assumption skip because no removable volume was attached.
- A real debug APK with the current WebUI assets was installed on the emulator. Playwright reached the emulator-served app through ADB forwarding at 360×800: unauthenticated root returned 401, authenticated root and onboarding status returned 200, there was no horizontal overflow, and no browser console error was observed. Machine-readable evidence is stored at `.scratch/emulator-artifacts/m7-live-webui-smoke.json`.
- `WEBUI_PASSWORD=<redacted> node e2e/m4-runner.mjs` completed on the final current tree in 304 seconds. The deterministic live flow passed authenticated WebSocket frames, owned fixtures, destination reuse/differences, canonical paths, collision-safe adds, completed/partial/conflict/native-failure moves, unavailable storage, interrupted retry/cancel, resume, and recovery. Normal cleanup verified no owned fixture roots, runner/fixture process, daemon service/listener, host listener, virtual removable volume, or ADB forward remained. Redacted log: `.scratch/emulator-artifacts/m4-live-runner-long-final.log`.
- No physical Android host, APK installation on a physical device, phone browser run, or separate LAN desktop browser run occurred.

**Smallest next experiment:** perform the separately recorded physical Android-host browser and separate-LAN desktop-browser acceptance run. Emulator and ADB-forwarded Chromium evidence must remain labeled emulator-only.
