# Stage 1 Status — 2026-06-28

## Goal

Prove that our own Android app can embed libtorrent-rasterbar and manage a real torrent session safely.

## What Works (PASS)

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | Build debug APK | PASS | `./gradlew assembleDebug` — BUILD SUCCESSFUL, ~72MB APK |
| 2 | Install on device | PASS | `adb install -r` — Success on Samsung Galaxy S23 (SM-N985F), Android 13, arm64-v8a |
| 3 | App launches | PASS | `adb shell am start` — no crashes |
| 4 | Native library loads | PASS | `TorrentJNI: Session 1 created, save_path=...` |
| 5 | libtorrent session starts | PASS | Listening on TCP+uTP port 6881, external IP received |
| 6 | Diagnostics screen | PASS | Shows ABI=arm64-v8a, libtorrent=2.0.10.0, Native loaded=yes, Session started=yes |
| 7 | Add magnet | PASS | Magnet added, torrent appears in UI with name, state, progress, rates, peers, save path |
| 8 | UI reflects native state | PASS | State shows `downloading_metadata`, name shows `ubuntu-24.04.1-desktop-amd64.iso` |

## What Needs Fixing

### Crash on "Test" button (BLOCKER)

- **Symptom:** App crashes after tapping "Test" button (second run with alert logging enabled).
- **Likely cause:** Enabling `status_notification | tracker_notification | connect_notification | peer_notification | performance_warning` in the alert mask produces many more alerts. The `nativePopAlerts()` call logs every alert via `LOGI("Alert: %s", ...)`. This may be causing a crash due to:
  - Alert pointer invalidation (alerts are freed after `pop_alerts()` returns)
  - Main thread blocking from excessive log output
  - The `LOGI` call accessing `alert->message()` after the alert is freed
- **Fix needed:** Store the alert message string before logging, or reduce alert mask back to `error_notification | storage_notification` and add targeted logging.

### Magnet stuck in `downloading_metadata`

- **Symptom:** Torrent stays in `downloading_metadata` state with 0-2 peers and 0 B/s download.
- **Root cause:** The Ubuntu 24.04 magnet (`2e62854a...`) has no active peers with metadata available. This is a magnet/seed issue, not a code bug.
- **Evidence:** Session is listening correctly, external IP is obtained, peers connect briefly but metadata is never received.
- **Fix needed:** Use a well-seeded magnet. Candidates:
  - OpenStreetMap extracts (large, many seeders)
  - Arch Linux ISO (smaller, good seeder count)
  - Or any magnet confirmed to have active seeders at test time

### Pause/Resume not visible

- **Symptom:** Tap "Pause" — log shows `Pausing torrent 1` but UI state doesn't change to "paused".
- **Root cause:** During `downloading_metadata`, libtorrent's `torrent_status::state` stays at `downloading_metadata` even when paused. The torrent is paused internally but the state enum doesn't reflect it until metadata is received.
- **Fix needed:** Check `torrent_handle::is_paused()` or `torrent_status::flags` for the paused bit, not just the state enum. Or accept this as a known Stage 1 limitation.

### State doesn't survive app restart

- **Symptom:** Kill app, relaunch — torrents are gone.
- **Status:** EXPECTED. Documented Stage 1 limitation (ADR-008). No session persistence implemented.

## Files Changed This Session

| File | Change |
|------|--------|
| `app/src/main/jni/torrent_jni.cpp` | Alert mask expanded, alert logging added, pause/resume/remove logging, state code logging, deprecated API replaced, format specifiers fixed |
| `app/src/main/java/com/andreiefimov/torrentwebui/TorrentSession.kt` | State code mapping fixed for libtorrent 2.0.10, `popAlerts()`, `nativeGetSavePath()`, savePath populated |
| `app/src/main/java/com/andreiefimov/torrentwebui/TorrentViewModel.kt` | `TEST_MAGNET` constant, `addTestMagnet()`, `popAlerts()` in polling, Log.d for polling |
| `app/src/main/java/com/andreiefimov/torrentwebui/MainActivity.kt` | "Test" button added, savePath displayed in torrent card |
| `app/build.gradle.kts` | Namespace `com.andreiefimov.torrentwebui` |
| `ARCHITECTURE.md` | Updated |
| `BUILD_AND_RUN.md` | Updated |
| `TEST_REPORT.md` | Updated |
| `THIRD_PARTY_NOTICES.md` | Updated |
| `DECISIONS.md` | Updated |
| `.gitignore` | `!dep/boost-sha256.txt` |
| `dep/boost-sha256.txt` | New — SHA-256 checksum for Boost 1.86.0 |
| `scripts/bootstrap-deps.sh` | New — Boost download + verification script |

## Known Uncommitted Changes

All changes are in the working tree. Last commit: `b64d0d0` (Stage 1 hardening: namespace rename, JNI fixes, Boost reproducibility).

Additional changes since that commit:
- State code mapping fix (libtorrent 2.0.10 enum alignment)
- Alert logging in `nativePopAlerts()`
- Pause/resume/remove logging
- State code logging in `nativeGetTorrentStatus()`
- "Test" button + `TEST_MAGNET` constant
- `savePath` populated in `TorrentStatus`
- Polling Log.d in ViewModel

## Next Steps (Priority Order)

1. **Fix crash on "Test" button** — reduce alert mask or fix alert message lifetime in `nativePopAlerts()`.
2. **Use well-seeded magnet** — replace `TEST_MAGNET` with one that has active seeders.
3. **Fix pause visibility** — check `is_paused()` flag or `torrent_status::flags` instead of state enum alone.
4. **Complete acceptance tests** — pause, resume, remove+delete, screen rotation.
5. **Remove debug artifacts** — `TEST_MAGNET`, "Test" button, verbose logging.
6. **Commit final state** and update `TEST_REPORT.md` with actual pass/fail.

## Stage 1 Limitations (Documented)

- No session persistence across process death (ADR-008)
- No WebUI
- No SAF integration
- No foreground service
- Polling-based updates (ADR-009)
- Magnet-only (no .torrent file support)
