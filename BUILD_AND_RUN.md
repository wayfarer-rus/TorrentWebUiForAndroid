# Build and Run

## Prerequisites

- Android SDK platform `android-36.1`
- Android NDK `29.0.14206865`
- CMake `3.22.1`
- JDK 17
- Node.js 20.19 or newer (Node.js 22 LTS recommended)
- ADB for installation and device diagnostics

The current APK targets Android 16, supports Android 13+ (`minSdk 33`), and builds only `arm64-v8a`.

## Clone and bootstrap

```bash
git clone --recurse-submodules https://github.com/wayfarer-rus/TorrentWebUiForAndroid.git
cd TorrentWebUiForAndroid
./scripts/bootstrap-deps.sh
cd web
npm ci
cd ..
```

If Android Studio has not generated `local.properties`, create it with your SDK location:

```properties
sdk.dir=/path/to/Android/sdk
```

## Build the APK

```bash
./gradlew :app:assembleDebug --console=plain
```

Gradle builds the SvelteKit WebUI, copies it into APK assets, generates the packaged open-source notices, and compiles the native libtorrent/JNI layer. The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install and start

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.andreiefimov.torrentwebui/.MainActivity
```

Complete Android onboarding, grant notification and All Files Access through system UI, choose a download folder, and replace the bootstrap WebUI password. Open the LAN URL shown by the app from a trusted-network browser.

Do not expose the WebUI port to the public Internet. See [SECURITY.md](SECURITY.md).

## WebUI development

```bash
cd web
npm ci
npm run dev
```

The development server uses mocked/browser-test seams; Android remains the authority for real storage, daemon, and authentication behavior.

## Host-side validation

```bash
cd web
npm run check
npm run test:policy
npx playwright install chromium
npm run test:browser
```

Run JVM tests and Android lint from the repository root:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug --console=plain
```

## Emulator/device acceptance

Instrumentation and live native/storage runners require an isolated Android emulator, visible permission interaction, and additional cleanup guarantees. See [EMULATOR_REFERENCE.md](EMULATOR_REFERENCE.md), [TEST_REPORT.md](TEST_REPORT.md), and the runner scripts under `web/e2e/`.

Never claim a physical-device or separate-LAN result unless that environment was actually used.
