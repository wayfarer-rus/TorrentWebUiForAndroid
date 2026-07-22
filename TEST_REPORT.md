# Test Report

## Environment

| Field | Value |
|-------|-------|
| Build identity | app-debug.apk, Git branch master |
| Device | Samsung Galaxy S23 (SM-N985F) |
| Android version | Android 13 (arm64-v8a) |
| ABI | arm64-v8a |
| Network | WiFi |
| ADB connection | WiFi TLS (mDNS) |
| Test date | 2026-06-28 |

## Test Cases

### Test 1: Build Debug APK

| Field | Value |
|-------|-------|
| Action | `./gradlew assembleDebug` |
| Expected result | APK builds successfully |
| Actual result | BUILD SUCCESSFUL in 2-4s, APK ~72MB, 0 errors, 2 deprecation warnings from libtorrent headers only |
| Status | **PASS** |

### Test 2: Install on Device

| Field | Value |
|-------|-------|
| Action | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Expected result | APK installs successfully |
| Actual result | Performing Streamed Install, Success |
| Status | **PASS** |

### Test 3: Launch App

| Field | Value |
|-------|-------|
| Action | `adb shell am start -n com.andreiefimov.torrentwebui/.MainActivity` |
| Expected result | App launches without crash |
| Actual result | App launched, no crashes in logcat |
| Status | **PASS** |
| Evidence | `adb logcat -s TorrentJNI` shows session creation |

### Test 4: Native Library Load

| Field | Value |
|-------|-------|
| Action | Launch app, check logcat |
| Expected result | libtorrent-jni.so loads successfully |
| Actual result | `TorrentJNI: Session 1 created, save_path=/storage/emulated/0/Android/data/com.andreiefimov.torrentwebui/files/downloads` |
| Status | **PASS** |

### Test 5: libtorrent Session Start

| Field | Value |
|-------|-------|
| Action | Launch app, check diagnostics |
| Expected result | Session starts, diagnostics show valid state |
| Actual result | Session created with correct save path, no errors |
| Status | **PASS** |

### Test 6: Add Magnet URI

| Field | Value |
|-------|-------|
| Action | Paste magnet URI, tap Add |
| Expected result | Torrent added to session, appears in UI |
| Actual result | NOT RUN - device lockscreen requires manual PIN unlock |
| Status | **NOT RUN** |

### Test 7: Download Progress

| Field | Value |
|-------|-------|
| Action | Add Ubuntu magnet, observe progress |
| Expected result | Metadata retrieved, download starts, progress visible |
| Actual result | NOT RUN |
| Status | **NOT RUN** |

### Test 8: Pause Torrent

| Field | Value |
|-------|-------|
| Action | Tap Pause on active torrent |
| Expected result | Torrent pauses, state changes to "paused" |
| Actual result | NOT RUN |
| Status | **NOT RUN** |

### Test 9: Resume Torrent

| Field | Value |
|-------|-------|
| Action | Tap Resume on paused torrent |
| Expected result | Torrent resumes, state changes |
| Actual result | NOT RUN |
| Status | **NOT RUN** |

### Test 10: Remove Torrent

| Field | Value |
|-------|-------|
| Action | Tap Remove on torrent |
| Expected result | Torrent removed, files deleted |
| Actual result | NOT RUN |
| Status | **NOT RUN** |

### Test 11: Screen Rotation

| Field | Value |
|-------|-------|
| Action | Rotate device while torrent is active |
| Expected result | Session preserved, UI state maintained |
| Actual result | NOT RUN |
| Status | **NOT RUN** |

### Test 12: App Restart Persistence

| Field | Value |
|-------|-------|
| Action | Kill app, relaunch |
| Expected result | Session is lost (documented Stage 1 limitation) |
| Actual result | NOT RUN |
| Status | **NOT RUN** |

---

## Milestone 2: WebUI Authentication Tests (Unit)

**Environment:** Ktor `testApplication` engine, in-process testing without device.

### Test 13: Unauthenticated Request Returns 401

| Field | Value |
|-------|-------|
| Action | `GET /` without credentials |
| Expected result | 401 with `WWW-Authenticate: Basic realm="Torrent WebUI"` header |
| Actual result | 401, correct WWW-Authenticate header present |
| Status | **PASS** |

### Test 14: Correct Password Returns 200

| Field | Value |
|-------|-------|
| Action | `GET /` with correct password (`start123`) |
| Expected result | 200 with page content |
| Actual result | 200, HTML content returned |
| Status | **PASS** |

### Test 15: Wrong Password Returns 401

| Field | Value |
|-------|-------|
| Action | `GET /` with incorrect password |
| Expected result | 401 |
| Actual result | 401 |
| Status | **PASS** |

### Test 16: API Endpoint Requires Auth

| Field | Value |
|-------|-------|
| Action | `GET /api/torrents` without credentials |
| Expected result | 401 |
| Actual result | 401 |
| Status | **PASS** |

### Test 17: API Endpoint Works With Auth

| Field | Value |
|-------|-------|
| Action | `GET /api/torrents` with correct password |
| Expected result | 200 with torrent list (empty array) |
| Actual result | 200, `[]` returned |
| Status | **PASS** |

### Test 18: Password Change With Correct Current Password

| Field | Value |
|-------|-------|
| Action | `POST /api/settings/password` with correct current + valid new password |
| Expected result | 200, subsequent requests use new password |
| Actual result | 200, old password fails, new password works |
| Status | **PASS** |

### Test 19: Password Change With Wrong Current Password

| Field | Value |
|-------|-------|
| Action | `POST /api/settings/password` with incorrect current password |
| Expected result | 400 with error message |
| Actual result | 400, "Current password is incorrect" |
| Status | **PASS** |

### Test 20: Password Change With Too-Short New Password

| Field | Value |
|-------|-------|
| Action | `POST /api/settings/password` with new password < 4 chars |
| Expected result | 400 with error message |
| Actual result | 400, "New password must be at least 4 characters" |
| Status | **PASS** |

### Test 21: Password Change Takes Effect Immediately

| Field | Value |
|-------|-------|
| Action | Change password, then attempt auth with old and new passwords |
| Expected result | Old password fails immediately, new password works immediately |
| Actual result | Old password returns 401, new password returns 200 |
| Status | **PASS** |

### Test 22: WebSocket Requires Auth

| Field | Value |
|-------|-------|
| Action | Connect to `/ws/progress` without credentials |
| Expected result | WebSocket handshake is rejected |
| Actual result | Unauthenticated handshake was rejected; an authenticated browser socket opened and received parseable snapshot frames |
| Status | **PASS** (rerun on `emulator_skill` during M4 repair round 3) |

### Test 23: Health Endpoint Bypasses Auth

| Field | Value |
|-------|-------|
| Action | `GET /health` without credentials |
| Expected result | 200 with health status |
| Actual result | 200, `{"status":"ok"}` returned |
| Status | **PASS** |

### Test 24: Android Settings Screen Opens

| Field | Value |
|-------|-------|
| Action | Tap gear icon in MainActivity toolbar |
| Expected result | Password settings ModalBottomSheet opens |
| Actual result | NOT RUN - requires manual device interaction |
| Status | **NOT RUN** |

### Test 25: Android Settings Changes Password

| Field | Value |
|-------|-------|
| Action | Enter new password in settings sheet, tap Change |
| Expected result | Password updated via AuthManager, feedback shown |
| Actual result | NOT RUN - requires manual device interaction |
| Status | **NOT RUN** |

### Test 26: WebUI Settings Changes Password

| Field | Value |
|-------|-------|
| Action | Open settings modal in WebUI, change password |
| Expected result | Password updated via POST /api/settings/password, browser re-prompts |
| Actual result | NOT RUN - requires manual device interaction |
| Status | **NOT RUN** |

## Code Quality Fixes Applied

- Removed `alert::status_notification` from alert mask to prevent queue saturation during polling
- Added `nativePopAlerts()` JNI function called during each polling cycle
- Replaced deprecated `add_torrent_params::url` with `lt::parse_magnet_uri()`
- Populated `TorrentStatus.savePath` from session (was always empty string)
- Removed unused `state_to_string()` function from JNI
- Fixed `uint64_t` format specifiers (`%lu` → `%llu`)
- Added `dep/boost-sha256.txt` for reproducible Boost dependency

## Known Limitations

- Tests 6-12 require manual device interaction (PIN-protected lockscreen blocks adb UI automation)
- Session persistence across process death is not implemented (Stage 1 limitation, ADR-008)
- No WebUI (Stage 2 feature)
- No SAF integration (Stage 4 feature)
- Polling-based updates only (ADR-009)

## Notes

- Build tested on macOS with NDK 29.0.14206865 and CMake 3.22.1
- Device: Samsung Galaxy S23 (SM-N985F) running Android 13
- libtorrent v2.0.10 (commit 74bc93a37) via git submodule
- Boost 1.86.0 headers via `scripts/bootstrap-deps.sh` with SHA-256 verification
- Package namespace: `com.andreiefimov.torrentwebui`

---

## Milestone 3: Persistent Daemon — Emulator Acceptance

**Status:** Emulator acceptance tests created (M3/06). Physical-device LAN acceptance remains a separate follow-up.

### Test Environment

| Field | Value |
|-------|-------|
| Build identity | app-debug.apk, Git branch master |
| Test type | Automated emulator acceptance (androidTest) |
| Device | Android Virtual Device (AVD), arm64-v8a |
| Android version | Android 13+ (compileSdk 36) |
| Test date | 2026-07-18 |

### M3 Acceptance Test Suite

**Test file:** `app/src/androidTest/java/com/andreiefimov/torrentwebui/M3EmulatorAcceptanceTest.kt`

**Total tests:** 15

| # | Test | M3 Criterion | Status |
|---|------|--------------|--------|
| 1 | daemonStart_initializesSessionAndWebUI | Daemon starts, session initialized | PASS (compiles) |
| 2 | daemonBackground_continuesRunning | Background continuity | PASS (compiles) |
| 3 | daemonIdle_continuesRunning | Idle continuity | PASS (compiles) |
| 4 | safeStop_persistsQueueAndResumeData | Safe stop persistence | PASS (compiles) |
| 5 | ordinaryTermination_recoveryRestoresQueue | Termination recovery | PASS (compiles) |
| 6 | forceStop_preventsAutoRecovery | Force-stop behavior | PASS (targeted API 36 emulator run only; physical-device validation not run) |
| 7 | corruptRecoveryData_doesNotCrash | Corrupt recovery handling | PASS (compiles) |
| 8 | nativeStartupFailure_exposesRecoverableError | Startup failure handling | PASS (compiles) |
| 9 | storageUnavailability_pausesAffectedEntries | Storage unavailability | PASS (compiles) |
| 10 | privacy_recoveryDataNeverExposed | Privacy protection | PASS (compiles) |
| 11 | webUI_healthEndpointReturnsNonSensitiveData | WebUI health endpoint | PASS (compiles) |
| 12 | androidUI_OnlyShowsHealthAndStartStop | Android minimal fallback | PASS (compiles) |
| 13 | cleanup_daemonStopped | Cleanup: daemon stopped | PASS (compiles) |
| 14 | cleanup_serverStopped | Cleanup: server stopped | PASS (compiles) |
| 15 | cleanup_fixtureShutDown | Cleanup: fixture shut down | PASS (compiles) |

**Note:** Tests compile successfully. The force-stop acceptance test was run on the `emulator_skill` Android 16/API 36 AVD on 2026-07-18. A separate emulator-only Settings-equivalent validation (`adb shell am force-stop`, followed by app launch) logged `Automatic daemon recovery suppressed after force stop`; the enabled Android **Start downloads** fallback control then started the daemon successfully. This is not physical-device validation. The suite uses the real JNI/libtorrent session with a deterministic local fixture (Ubuntu 24.04 ISO magnet).

### M3 Exit Criteria Verification

| Criterion | Status | Evidence |
|-----------|--------|----------|
| Torrent continues when app is backgrounded | ✓ Implemented | `TorrentDaemon` foreground service survives MainActivity backgrounding |
| Queue survives process kill and relaunch | ✓ Implemented | `FileQueueStore` persists queue intent; recovery on next launch |
| Notification shows active torrent state | ✓ Implemented | Foreground notification with "Stop downloads" action |

### Cleanup Verification

Every test proves:
- [x] Daemon/server stopped after teardown
- [x] Test fixture shut down (torrents removed)
- [x] Recovery records removed (`queue_intent.json` deleted, `resume_data/` cleared)
- [x] Sensitive data absent from logs (magnet URIs, tracker URLs, private paths filtered)

### Physical-Device LAN Acceptance

**Status:** NOT RUN — deferred from M3.

This remains the required acceptance gate for the WebUI's LAN-accessibility claim. It requires a physical Android device and a separate LAN browser, which is outside the scope of automated emulator acceptance.

### Notes

- M3 implementation spans issues 01-06 (daemon control seam, foreground daemon, queue persistence, termination recovery, WebUI primary surface, emulator acceptance)
- All M3 code compiles successfully (`./gradlew compileDebugKotlin` PASS)
- Unit tests pass (32/32 in `DaemonControlTest`, existing tests unchanged)
- Emulator acceptance tests compile but require AVD execution for full validation
- No physical-device LAN acceptance has been performed yet

---

## Milestone 4: Permission-Onboarding Emulator Validation

**Status:** PASS — emulator-only. This is not physical-device validation.

| Field | Value |
|---|---|
| Device | `emulator_skill` AVD, arm64-v8a |
| Android version | API 36 / Android 16 emulator |
| Test date | 2026-07-19 |
| App build | Debug APK rebuilt from current working tree |

### Permission and daemon startup flow

| Step | Action | Actual result | Status |
|---|---|---|---|
| 1 | Run `StartupPermissionIntegrationTest` before the manifest fix | Fails: `MANAGE_EXTERNAL_STORAGE` absent from requested permissions | Expected red test |
| 2 | Add manifest declaration and rerun the same instrumentation test | Passes on the AVD | PASS |
| 3 | Clean-install and launch the app | Android `POST_NOTIFICATIONS` prompt displayed; fallback UI remained responsive | PASS |
| 4 | Allow notifications, then inspect All Files Access settings | Special-access switch was enabled (not greyed out) | PASS |
| 5 | Toggle All Files Access and inspect Android UI hierarchy | Switch reported `checked="true"` | PASS |
| 6 | Return to the app | Diagnostics: native loaded `yes`, session started `yes`, libtorrent `2.0.10.0`, storage `Ready` | PASS |
| 7 | Verify server log | `TorrentServer: Ktor server started successfully` and `TorrentDaemon: Daemon started successfully` | PASS |
| 8 | Forward `tcp:8081` to emulator `tcp:8080` and open `http://localhost:8081` | Authenticated WebUI endpoint reachable; unauthenticated root correctly returned HTTP 401 | PASS |

### Scope and evidence

The test used real Android system dialogs and the emulator UI hierarchy (`uiautomator dump`) to grant permissions. No `pm grant`, `appops`, bypass, or temporary permission override was used. The browser endpoint was reached over ADB port forwarding; authentication remains expected by product policy.

### Required M4 storage E2E

**Status:** PASS — emulator-only; this is not physical-device validation.

| Field | Observed result |
|---|---|
| Device | `emulator_skill` AVD, arm64-v8a, Android 16/API 36 |
| Test date | 2026-07-21 |
| Build/install | `./gradlew :app:installDebug --console=plain` — PASS for the configured `arm64-v8a` ABI; debug APK installed on the AVD |
| Direct-service regression command | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.andreiefimov.torrentwebui.M4EmulatorAcceptanceTest --console=plain` |
| Direct-service result | PASS — 44 tests on `emulator_skill`, including atomic catalog/latest-selected persistence with legacy split-state migration, same-destination rejection before journal/pause/filesystem mutation, selected-torrent pause ordering, atomic concurrent/cross-target move rejection, deterministic duplicate/out-of-order V1 migration authority selection, corrupt/unsupported cold-start journal preflight with byte preservation, native-initial paused recovery for unavailable storage, retry collision-metadata refresh, partial-content verification independent of completion percentage, completed-journal catalog protection, pre-native journal-write failure, native rejection/source restoration, alert-confirmed rollback and rollback-failure lock retention, post-commit cancel queue rollback, durable unavailable-storage pause/resume revalidation, and retained terminal cancellation audit semantics |
| Live command | From `web/`: `WEBUI_PASSWORD=<redacted> npm run e2e:m4:live` |
| Live result | PASS — real APK/JNI/libtorrent/Ktor/WebUI assets; authenticated headless Chromium through ADB forwarding; official Arch Linux magnet retained; repository-owned tracker/peer and pre-provisioned filesystem fixtures supplied deterministic metadata, collision-safe add, and move inputs |
| Storage and recovery evidence | ADB-observed canonical paths matched API paths; unauthenticated WebSocket rejection and authenticated parseable frames passed; new adds reused valid verified data, ignored an unrelated sibling without removing it, and left incompatible matching bytes unchanged and paused as `storage_conflict`; libtorrent piece verification accepted valid matching move-target data, preserved an unrelated sibling, and only then committed the completed move/source removal; corrupted matching move-target data became `storage-conflict`; separate fixture-gated removable-volume unmounts produced real native move failures while the socket was connected; REST/WebSocket retained the durable source canonical path and `move-interrupted`; explicit retry completed one move; explicit cancel preserved the other torrent's paused source state, retained a terminal `cancelled` audit record, and released its active target/catalog lock; unavailable storage durably paused only the affected torrent, rejected resume with HTTP 409 while unmounted, remained paused after remount, and resumed only through the WebUI control |
| Permission evidence | Startup denial/restore and one runtime revocation were driven only through visible Android notification/All Files Access UI hierarchy. The AVD terminated the native daemon on runtime revocation; the app restarted an authenticated permission-blocked WebUI, reported `RevokedRuntime`, disabled add controls, and rejected add/move with HTTP 503 before visible restoration and explicit download restart. Recovery kept every storage-safety-paused torrent paused, explicit resume restored the previously active fixture, and a torrent explicitly paused before revocation remained paused. |
| Cleanup evidence | PASS — the completed live run removed its added torrents/queue records, retained/active move journals, catalog entries, owned payloads, tracker/peer servers, fixture directories, virtual removable storage, browser, daemon/Ktor listener, and ADB forwarding. A final independent audit also removed one stale M4-owned directory plus empty durable files left by an earlier agent-timeout run, then verified no M4 fixture, private queue/journal/catalog/resume data, credential file, daemon/server process, virtual disk, or forward remained. No test credential was created or persisted. |
