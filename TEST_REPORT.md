# Test Report

## Environment

| Field | Value |
|-------|-------|
| Build identity | app-debug.apk, Git branch main |
| Device | Samsung Galaxy S23 (SM_N985F) |
| Android version | Android 13 (arm64-v8a) |
| ABI | arm64-v8a |
| Network | WiFi |
| Test date | 2026-06-28 |

## Test Cases

### Test 1: Build Debug APK

| Field | Value |
|-------|-------|
| Action | `./gradlew assembleDebug` |
| Expected result | APK builds successfully |
| Actual result | BUILD SUCCESSFUL in 1m 6s, APK ~72MB |
| Status | PASS |
| Evidence / log reference | Build log shows CMake configure + compile + link success |

### Test 2: Install on Device

| Field | Value |
|-------|-------|
| Action | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Expected result | APK installs successfully |
| Actual result | Performing Streamed Install, Success |
| Status | PASS |
| Evidence / log reference | adb install output |

### Test 3: Launch App

| Field | Value |
|-------|-------|
| Action | `adb shell am start -n com.example.torrentwebuiforandroid/.MainActivity` |
| Expected result | App launches without crash |
| Actual result | App launched, no crashes in logcat |
| Status | PASS |
| Evidence / log reference | `adb logcat` shows no AndroidRuntime crashes |

### Test 4: Native Library Load

| Field | Value |
|-------|-------|
| Action | Launch app, check logcat |
| Expected result | libtorrent-jni.so loads successfully |
| Actual result | `TorrentJNI: Session 1 created, save_path=/storage/emulated/0/Android/data/com.example.torrentwebuiforandroid/files/downloads` |
| Status | PASS |
| Evidence / log reference | `adb logcat \| grep TorrentJNI` |

### Test 5: libtorrent Session Start

| Field | Value |
|-------|-------|
| Action | Launch app, check diagnostics |
| Expected result | Session starts, diagnostics show valid state |
| Actual result | Session created with correct save path, no errors |
| Status | PASS |
| Evidence / log reference | `TorrentJNI: Session 1 created` log |

### Test 6: Add Magnet URI

| Field | Value |
|-------|-------|
| Action | Paste magnet URI, tap Add |
| Expected result | Torrent added to session, appears in UI |
| Actual result | NOT RUN - requires user interaction with specific magnet |
| Status | NOT RUN |
| Evidence / log reference | N/A |

### Test 7: Download Progress

| Field | Value |
|-------|-------|
| Action | Add Ubuntu magnet, observe progress |
| Expected result | Metadata retrieved, download starts, progress visible |
| Actual result | NOT RUN |
| Status | NOT RUN |
| Evidence / log reference | N/A |

### Test 8: Pause Torrent

| Field | Value |
|-------|-------|
| Action | Tap Pause on active torrent |
| Expected result | Torrent pauses, state changes to "paused" |
| Actual result | NOT RUN |
| Status | NOT RUN |
| Evidence / log reference | N/A |

### Test 9: Resume Torrent

| Field | Value |
|-------|-------|
| Action | Tap Resume on paused torrent |
| Expected result | Torrent resumes, state changes |
| Actual result | NOT RUN |
| Status | NOT RUN |
| Evidence / log reference | N/A |

### Test 10: Remove Torrent

| Field | Value |
|-------|-------|
| Action | Tap Remove on torrent |
| Expected result | Torrent removed, files deleted |
| Actual result | NOT RUN |
| Status | NOT RUN |
| Evidence / log reference | N/A |

### Test 11: Screen Rotation

| Field | Value |
|-------|-------|
| Action | Rotate device while torrent is active |
| Expected result | Session preserved, UI state maintained |
| Actual result | NOT RUN |
| Status | NOT RUN |
| Evidence / log reference | N/A |

### Test 12: App Restart Persistence

| Field | Value |
|-------|-------|
| Action | Kill app, relaunch |
| Expected result | Session is lost (documented limitation) |
| Actual result | NOT RUN |
| Status | NOT RUN |
| Evidence / log reference | N/A |

## Known Limitations

- Tests 6-12 require manual device interaction and could not be fully automated
- Session persistence across process death is not implemented (Stage 1 limitation)
- No WebUI (Stage 2 feature)
- No SAF integration (Stage 4 feature)

## Notes

- Build was tested on macOS with NDK 29.0.14206865 and CMake 3.22.1
- Device validation was performed on Samsung Galaxy S23 (SM_N985F) running Android 13
- libtorrent v2.0.10 (commit 74bc93a37) was used
- Boost 1.86.0 headers were downloaded from archives.boost.io
