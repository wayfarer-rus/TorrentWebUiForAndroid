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
