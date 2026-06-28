# Build and Run

## Prerequisites

- **Android SDK** with platform `android-36.1` (or higher)
- **Android NDK** 29.0.14206865
- **CMake** 3.22.1
- **Java 17** (JDK)
- **Gradle** 9.4.1 (via wrapper)

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
   ```

3. Ensure `local.properties` has correct SDK path:
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
adb shell am start -n com.example.torrentwebuiforandroid/.MainActivity
```

## Stage 1 Test Flow

1. Launch the app
2. Tap the info icon (top-right) to open Diagnostics panel
3. Verify:
   - ABI shows `arm64-v8a`
   - libtorrent version shows `2.0.10`
   - Native loaded: yes
   - Session started: yes
4. Paste a magnet URI into the text field
5. Tap "Add"
6. Observe torrent appear in list with state, progress, rates, peers
7. Tap "Pause" to pause the torrent
8. Tap "Resume" to resume
9. Tap "Remove" to remove torrent and delete files

## Expected Download Location

```
/storage/emulated/0/Android/data/com.example.torrentwebuiforandroid/files/downloads/
```

This is app-private external storage via `getExternalFilesDir("downloads")`.

## Logcat

```bash
adb logcat -s "TorrentJNI"
```
