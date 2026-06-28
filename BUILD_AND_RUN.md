# Build and Run

## Prerequisites

- **Android SDK** with platform `android-36.1` (or higher)
- **Android NDK** 29.0.14206865
- **CMake** 3.22.1
- **Java 17** (JDK)
- **Gradle** (via wrapper, no separate install needed)

## Setup

1. Clone the repository with submodules:
   ```bash
   git clone --recurse-submodules <repo-url>
   cd TorrentWebUiForAndroid
   ```

2. If submodules were not cloned:
   ```bash
   git submodule update --init libtorrent
   cd libtorrent && git checkout v2.0.10 && git submodule update --init deps/try_signal deps/asio-gnutls
   cd ..
   ```

3. Bootstrap pinned dependencies (Boost headers):
   ```bash
   ./scripts/bootstrap-deps.sh
   ```
   This downloads Boost 1.86.0 from `archives.boost.io`, verifies SHA-256 checksum, and extracts to `dep/`. The checksum is pinned in `dep/boost-sha256.txt` and in the script.

4. Ensure `local.properties` has correct SDK path:
   ```
   sdk.dir=/path/to/Android/sdk
   ```

## Build

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk` (~72MB)

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Launch

```bash
adb shell am start -n com.andreiefimov.torrentwebui/.MainActivity
```

## Stage 1 Test Flow

1. Launch the app
2. Tap the info icon (top-right) to open Diagnostics panel
3. Verify:
   - ABI shows `arm64-v8a`
   - libtorrent version shows `2.0.10`
   - Native loaded: yes
   - Session started: yes
4. Paste a magnet URI into the text field (see Legal Test Magnets below)
5. Tap "Add"
6. Observe torrent appear in list with state, progress, rates, peers, save location
7. Tap "Pause" to pause the torrent
8. Tap "Resume" to resume
9. Tap "Remove" to remove torrent and delete files

## Legal Test Magnets

Use only legal, publicly available torrents for testing:

- **Ubuntu 24.04 LTS Desktop** (official release):
  ```
  magnet:?xt=urn:btih:2e62854a660074367b8104bd09472b04b44d870e&dn=ubuntu-24.04.1-desktop-amd64.iso&tr=udp://tracker.opentrackr.org:1337/announce&tr=udp://open.stealth.si:80/announce
  ```

## Expected Download Location

```
/storage/emulated/0/Android/data/com.andreiefimov.torrentwebui/files/downloads/
```

This is app-private external storage via `getExternalFilesDir("downloads")`.

## Logcat

```bash
# Watch JNI logs
adb logcat -s "TorrentJNI"

# Watch all app logs
adb logcat | grep "com.andreiefimov.torrentwebui"
```
