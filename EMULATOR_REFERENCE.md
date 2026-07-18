# Android Emulator Reference

This file documents the emulator setup **once** so it never has to be looked up again.

## Prerequisites (all met)

| Component | Path | Status |
|-----------|------|--------|
| Android SDK | `~/Library/Android/sdk` | ✓ installed |
| Emulator binary | `~/Library/Android/sdk/emulator/emulator` | ✓ installed |
| ADB binary | `~/Library/Android/sdk/platform-tools/adb` | ✓ installed (in PATH via `.bashrc`) |
| SDK Manager | `~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager` | ✓ installed |
| System image | `system-images/android-36;default;arm64-v8a` (Android 16 / API 36) | ✓ installed |
| AVD | `emulator_skill` (Pixel 5, arm64-v8a) | ✓ created |

## One-Line Emulator Start

```bash
emulator -avd emulator_skill -no-window -no-audio -gpu swiftshader_indirect &
# Wait ~30s for boot, then: adb devices → should show "device"
```

## One-Line Emulator Stop

```bash
adb shell reboot -p
# or: killall -9 emulator 2>/dev/null; adb kill-server && adb start-server
```

## Quick Test Pattern (WebUI)

```bash
# Forward emulator port 8080 to host port 8081 (one-time per session)
adb forward tcp:8081 tcp:8080

# Test health endpoint from macOS
curl -s http://localhost:8081/health

# Test WebUI root
curl -s http://localhost:8081/ | head -5

# Check logcat
adb logcat -s 'TorrentServer:I' 'TorrentSession:I'
```

## Install APK

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `adb: command not found` | Add to PATH: `export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"` |
| Emulator won't start | Check port conflicts: `lsof -i :5554`; try `-ports 5584,5585` |
| ADB shows "offline" | `adb kill-server && adb start-server` then wait 10s |
| Boot takes >2 min | Check logs: `emulator -avd emulator_skill -verbose 2>&1 \| grep -i error` |
| APK install fails | `adb shell pm clear com.andreiefimov.torrentwebui` then retry install |

## Skill Reference

Full skill documentation: `~/.agents/skills/android-emulator/SKILL.md`
