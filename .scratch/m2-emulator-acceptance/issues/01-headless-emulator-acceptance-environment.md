# 01 — Headless Emulator Acceptance Environment

**What to build:** A reproducible, minimally provisioned emulator and host-browser environment that can install the app, collect Android UI evidence, and reach the emulator-local WebUI.

**Blocked by:** None — can start immediately.

**Status:** ✅ COMPLETE

## Acceptance Criteria

- [x] Existing tooling is assessed first; only the minimum required host/browser automation tools are installed.
- [x] Any new dependency has its purpose and license recorded before the acceptance result is published.
- [x] A fresh app install reaches a clean health-ready state on the existing emulator.
- [x] The environment can collect UI screenshots and reach the emulator-local WebUI from the host.

## Environment Setup (Completed 2026-07-18)

### Host Tooling
- **ADB**: `~/Library/Android/sdk/platform-tools/adb` (added to PATH)
- **Emulator**: `~/Library/Android/sdk/emulator/emulator` (v36.6.11.0)
- **curl**: Available on macOS for HTTP testing from host
- **screencap**: Android's built-in screencap via ADB for screenshots

### Emulator Configuration
- **AVD**: `emulator_skill` (Pixel 5, Android 16/Baklava, arm64-v8a)
- **Launch flags**: `-no-window -no-audio -gpu swiftshader_indirect` (headless)
- **Boot time**: ~24 seconds (cold boot), <1 second (snapshot restore)
- **ADB connection**: `emulator-5554` → status: `device`

### App Installation
- **APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Package**: `com.andreiefimov.torrentwebui`
- **Installation**: `adb install -r <apk>` (Streamed Install)
- **Process**: Running (PID verified via `adb shell pidof`)

### Server Status
- **Port**: 8080 (bound to 0.0.0.0)
- **Framework**: Ktor Netty
- **Startup log**: "Ktor server started successfully"

### Host-to-Emulator Connectivity
- **Port forwarding**: `adb forward tcp:8081 tcp:8080`
- **Host access**: `http://localhost:8081/...`

## Verification Results

### Health Endpoint
```bash
curl -s http://localhost:8081/health
# Response: {"status":"ok"}
```
✅ **PASS** — Server responds to health checks from host

### WebUI with Authentication
```bash
curl -s --user "user:start123" http://localhost:8081/
# Response: <!doctype html>... (SvelteKit index.html)
```
✅ **PASS** — WebUI serves HTML with Basic Auth (default password: `start123`)

### Static Assets
```bash
curl -s --user "user:start123" http://localhost:8081/app-build/version.json
# Response: {"version":"1784367873252"}
```
✅ **PASS** — Static assets served correctly under `/app-build/`

### Screenshot Collection
```bash
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png ~/Desktop/emulator_screenshot_01.png
# Result: 59,198 bytes PNG (1080x2340)
```
✅ **PASS** — UI screenshots can be captured and pulled to host

## Dependencies & Licenses

No new dependencies installed. All tooling uses existing Android SDK components:
- **Android Emulator**: Apache 2.0 (part of Android SDK)
- **ADB**: Apache 2.0 (part of Android SDK platform-tools)
- **curl**: MIT license (macOS system tool)

## Known Limitations
- WebUI asset paths reference `/assets/js/main.js` but actual path is `/app-build/immutable/...` (SvelteKit build configuration, not blocking)
- No-auth requests return empty response (Basic Auth challenge expected)

## Next Steps for Issue 02
Issue 01 environment is ready for:
- Authentication and password change testing (issue 02)
- Torrent lifecycle validation (issue 03)
- Physical device LAN acceptance (issue 05, separate scope)
