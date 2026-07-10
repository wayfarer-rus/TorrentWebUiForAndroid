---
name: android-emulator
description: Streamlined Android emulator lifecycle for end-to-end integration testing of Android apps.
metadata:
  type: skill
---

# Android Emulator Skill

Streamlined workflow for managing Android emulator lifecycle and running end-to-end integration tests on Android apps.

## When to Use

- After building an APK (`./gradlew assembleDebug`) and before running live tests
- When a ticket requires end-to-end verification on a real Android environment
- For testing native engine features (libtorrent JNI), network behavior, or file I/O that can't be verified in unit tests
- When the `testing-device-validation` skill identifies a need for emulator testing

## Prerequisites

This skill manages the full lifecycle. First-time setup requires:
1. Android SDK at `~/Library/Android/sdk` (check via `local.properties`)
2. Emulator binary available (`~/Library/Android/sdk/emulator/emulator`)
3. Network access to download system image (~400MB)

## Workflow

### 1. Initial Setup (One-Time)

On first use, the skill will:

```bash
# Install android-36 vanilla ARM64 system image (if not already installed)
~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager "system-images;android-36;default;arm64-v8a"

# Create AVD named "emulator_skill" (if not already exists)
echo "no" | ~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager create avd \
  --name emulator_skill \
  --package "system-images;android-36;default;arm64-v8a" \
  --device "pixel_5"

# Verify AVD exists
~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager list avd
```

**Notes:**
- AVD name: `emulator_skill` (consistent across sessions)
- Device template: Pixel 5 (good balance of performance and realism)
- Storage: 2GB internal storage is sufficient for testing

### 2. Start Emulator

```bash
# Launch emulator in headless mode (no GUI window)
~/Library/Android/sdk/emulator/emulator \
  -avd emulator_skill \
  -no-window \
  -no-audio \
  -gpu swiftshader_indirect &

# Wait for boot completion (typically 30-60 seconds)
echo "Waiting for emulator to boot..."
while ! adb devices | grep -q "device$"; do
  sleep 2
done
echo "Emulator ready!"
```

**Flags explained:**
- `-no-window`: Headless mode (essential for automation)
- `-no-audio`: Disable audio (faster boot, less resource usage)
- `-gpu swiftshader_indirect`: Software GPU rendering (fastest in headless mode)

### 3. Verify Connection

```bash
# Check ADB sees the emulator
adb devices

# Expected output:
# List of devices attached
# <emulator-id>    device

# Check Android version
adb shell getprop ro.build.version.release
# Expected: 14 (Android 14 = API 34, but system-image is android-36)

# Check ABI
adb shell getprop ro.product.cpu.abi
# Expected: arm64-v8a
```

### 4. Install APK

```bash
# Build and install in one command (from project root)
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or install separately if APK already built
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Verify installation
adb shell pm list packages | grep com.andreiefimov.torrentwebui
# Expected: package:com.andreiefimov.torrentwebui
```

### 5. Launch App (Optional)

```bash
# Start the main activity
adb shell am start -n com.andreiefimov.torrentwebui/.MainActivity

# Verify it's running
adb shell pidof com.andreiefimov.torrentwebui
# Expected: PID number (not empty)

# Check logcat for server startup
adb logcat -s 'TorrentServer:I' &
LOGCAT_PID=$!
sleep 3
adb shell logcat -d -s TorrentServer:I | head -20
kill $LOGCAT_PID 2>/dev/null
```

### 6. Run Tests

The skill supports interactive test execution. Present the user with options:

```
Android Emulator is ready!

Available commands:
  /emulator test <command>  - Run a command on the emulator (e.g., curl, ping)
  /emulator logcat          - Show recent logcat output
  /emulator screenshot      - Take a screenshot (saved to ~/Desktop/emulator_screenshot.png)
  /emulator files           - List app files and directories
  /emulator stop            - Stop the emulator
  /emulator restart         - Restart the emulator (preserves AVD)

What would you like to do?
```

**Common test patterns:**

#### HTTP Server Test (Ticket 10 example)

**Note:** Android emulator doesn't include `curl`. Use `adb forward` to map an
emulator port to a host port, then curl from macOS.

```bash
# Forward emulator port 8080 to host port 8081 (one-time per session)
adb forward tcp:8081 tcp:8080

# Test health endpoint from macOS
curl -s http://localhost:8081/health

# Expected: {"status":"ok"}

# Test root (serves index.html from assets)
curl -s http://localhost:8081/ | head -5

# Test asset files
curl -s http://localhost:8081/assets/js/main.js | head -3
```

**Inside-emulator alternative:** Android's `nc` connects but doesn't print responses.
For quick checks, verify the port is listening:
```bash
adb shell 'ss -tlnp | grep 8080'
# Expected: LISTEN on *:8080
```

#### Network Test (External URL)
```bash
# Test outbound connectivity
adb shell ping -c 3 8.8.8.8

# Expected: 3 packets transmitted, 3 received, 0% packet loss
```

#### File I/O Test
```bash
# Check app's internal storage
adb shell run-as com.andreiefimov.torrentwebui ls -la /data/data/com.andreiefimov.torrentwebui/files/

# Check downloads directory
adb shell ls -la /sdcard/Download/
```

#### Logcat Monitoring
```bash
# Tail logcat in real-time (Ctrl+C to stop)
adb logcat -s 'TorrentServer:I' 'NativeTorrentEngine:I' 'EventBus:I'

# Or capture last N lines
adb logcat -d -s 'TorrentServer:I' | tail -50
```

### 7. Stop Emulator

```bash
# Graceful shutdown
adb shell reboot -p

# Or force kill the emulator process
killall -9 emulator 2>/dev/null || true

# Verify it's stopped
adb devices
# Expected: List of devices attached (empty or only physical devices)
```

## Error Handling

### Emulator fails to start
```bash
# Check for port conflicts (emulator uses 5554-5588 by default)
lsof -i :5554

# Try with explicit port
~/Library/Android/sdk/emulator/emulator -avd emulator_skill -no-window -ports 5584,5585

# Check emulator logs
~/Library/Android/sdk/emulator/emulator -avd emulator_skill -no-window -verbose 2>&1 | head -100
```

### ADB can't see emulator
```bash
# Check if emulator process is running
ps aux | grep emulator

# Restart ADB server
adb kill-server && adb start-server

# Try connecting manually (if emulator shows IP)
adb connect localhost:5555
```

### Boot takes too long (>2 minutes)
```bash
# Check if emulator is actually running
adb devices

# If shows "offline", wait longer or restart
adb reboot

# Check emulator logs for errors
~/Library/Android/sdk/emulator/emulator -avd emulator_skill -no-window -verbose 2>&1 | grep -i error
```

### APK install fails
```bash
# Check disk space on emulator
adb shell df /data

# Clear app data and retry
adb shell pm clear com.andreiefimov.torrentwebui
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Check if APK is signed correctly
apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk
```

## Integration with Existing Skills

### With `testing-device-validation`
The `testing-device-validation` skill defines test categories:
- **Unit tests**: No device needed (run with `./gradlew test`)
- **Emulator tests**: UI layout, basic flows (use this skill)
- **Real-device tests**: Native engine, network, storage (required for production validation)

When `testing-device-validation` identifies a need for emulator testing, invoke this skill.

### With `android-native`
The `android-native` skill covers:
- Kotlin/Compose conventions
- Gradle version catalogs
- SDK/NDK/CMake discovery
- Target ABI (arm64-v8a)
- Permission policies

This skill focuses specifically on **emulator lifecycle and live testing**. Use `android-native` for development questions, this skill for testing.

### With `/implement`
When `/implement` completes work that requires live verification:
1. Build APK: `./gradlew assembleDebug`
2. Start this skill's emulator workflow
3. Run verification tests per the ticket's acceptance criteria
4. Report results back to `/implement`

## Performance Notes

- **Cold boot** (first time or after AVD delete): 60-90 seconds
- **Warm boot** (after stop/start): 15-30 seconds
- **Snapshot restore** (if enabled): 5-10 seconds (not enabled by default in this skill)

Resource usage:
- CPU: 1-2 cores (check with `htop`)
- RAM: ~1.5GB (check with `top`)
- Disk: ~2GB for system image + AVD data

## Troubleshooting Checklist

If the skill doesn't work:

1. **Is Android SDK installed?**
   ```bash
   ls ~/Library/Android/sdk/emulator/emulator
   ```

2. **Is system image installed?**
   ```bash
   ls ~/Library/Android/sdk/system-images/android-36/default/arm64-v8a/
   ```

3. **Does AVD exist?**
   ```bash
   ~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager list avd
   ```

4. **Is emulator process running?**
   ```bash
   ps aux | grep emulator | grep -v grep
   ```

5. **Is ADB connected?**
   ```bash
   adb devices
   ```

6. **Check emulator logs**
   ```bash
   cat ~/.android/avd/emulator_skill.avd/*.log 2>/dev/null || echo "No logs found"
   ```

## Example Session

```
User: /emulator

Claude: Starting Android emulator workflow...

[1/5] Checking system image...
✓ android-36 vanilla ARM64 installed

[2/5] Checking AVD...
✓ AVD 'emulator_skill' exists

[3/5] Starting emulator (headless)...
Emulator process started (PID 12345)

[4/5] Waiting for boot completion...
✓ Emulator booted and ADB connected (32s)

[5/5] Verifying setup...
✓ Android 14 (API 36) on arm64-v8a
✓ ADB device: emulator-5554

Android Emulator is ready!

Available commands:
  /emulator test <command>  - Run a command on the emulator (e.g., curl, ping)
  /emulator logcat          - Show recent logcat output
  /emulator screenshot      - Take a screenshot (saved to ~/Desktop/emulator_screenshot.png)
  /emulator files           - List app files and directories
  /emulator stop            - Stop the emulator
  /emulator restart         - Restart the emulator (preserves AVD)

What would you like to do?

User: /emulator test "curl -s http://localhost:8080/health"

Claude: Running test on emulator...
$ adb shell curl -s http://localhost:8080/health

{"status":"ok"}

✓ Test passed: Server is responding with JSON health check
```

## Future Enhancements (Not Implemented Yet)

- [ ] Snapshot support (faster boot by saving/restoring emulator state)
- [ ] Multiple AVD profiles (Pixel 5, Pixel 8, tablet layouts)
- [ ] Google Play images for tests requiring Google APIs
- [ ] Automated screenshot analysis (compare UI against expected state)
- [ ] Integration with CI/CD pipelines (headless mode optimization)
