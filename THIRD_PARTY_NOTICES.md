# Third-Party Notices

Track every third-party dependency used in this project.

| Dependency | Version / Commit | Source URL | License | Why Used | Bundled / Distributed | Notes |
|------------|------------------|------------|---------|----------|----------------------|-------|
| libtorrent-rasterbar | v2.0.10 (74bc93a37) | https://github.com/arvidn/libtorrent | Boost Software License 1.0 (BSD-style) | Native torrent engine | Bundled (git submodule) | Core torrent protocol implementation |
| Boost | 1.86.0 | https://archives.boost.io/release/1.86.0/source/boost_1_86_0.tar.gz | Boost Software License 1.0 | Required by libtorrent | Bundled (headers via bootstrap-deps.sh) | SHA-256 pinned in scripts/bootstrap-deps.sh; not tracked in git |
| AndroidX Compose BOM | 2025.03.01 | https://developer.android.com/jetpack/compose | Apache 2.0 | UI framework | Bundled (Gradle AAR) | Jetpack Compose UI |
| AndroidX Lifecycle | 2.8.7 | https://developer.android.com/jetpack/androidx/releases/lifecycle | Apache 2.0 | ViewModel, lifecycle management | Bundled (Gradle AAR) | ViewModel for session lifecycle |
| AndroidX Activity Compose | 1.10.1 | https://developer.android.com/jetpack/androidx/releases/activity | Apache 2.0 | Compose-Activity integration | Bundled (Gradle AAR) | ComponentActivity + setContent |
| Kotlin | 2.1.20 | https://kotlinlang.org | Apache 2.0 | Programming language | Toolchain | Android development language |
| Android NDK | 29.0.14206865 | https://developer.android.com/ndk | Android SDK License | Native build toolchain | Toolchain | Compiles libtorrent + JNI |
| CMake | 3.22.1 | https://cmake.org | BSD-3-Clause | Native build system | Toolchain | Builds native libraries |
| JUnit | 4.13.2 | https://junit.org | EPL 2.0 | Unit testing | Test-only | Local unit tests |
| AndroidX Test | 1.3.0 / 3.7.0 | https://developer.android.com/testing | Apache 2.0 | Instrumented testing | Test-only | Device instrumentation tests |
| OkHttp | 4.12.0 | https://github.com/square/okhttp | Apache 2.0 | HTTP client for Android instrumentation tests | Test-only | Used in M4 emulator acceptance suite (androidTest) |
| Playwright Test | 1.61.1 | https://github.com/microsoft/playwright | Apache 2.0 | Headless Chromium WebUI E2E acceptance | Test-only | Host-side test tool; not bundled in the APK |

## Dependency Bootstrap

Native dependencies (Boost) are not tracked in git. Run the bootstrap script after cloning:

```bash
./scripts/bootstrap-deps.sh
```

This script:
1. Downloads Boost 1.86.0 from the official archive
2. Verifies the SHA-256 checksum (`2575e74ffc3ef1cd0babac2c1ee8bdb5782a0ee672b1912da40e5b4b591ca01f`)
3. Extracts headers to `dep/`

The checksum is pinned in the script. If the upstream archive changes, the script will fail and must be updated with the new checksum.

## License Compatibility Notes

- **libtorrent-rasterbar** uses the Boost Software License 1.0, which is a permissive BSD-style license. It is NOT GPL. This license allows static linking and distribution in closed-source applications without requiring source disclosure.
- **Boost** uses the Boost Software License 1.0, also permissive.
- All AndroidX/Kotlin dependencies use Apache 2.0.
- No GPL dependencies are used in this project.

## Guidelines

- Add a row for every new dependency before merging.
- Avoid GPL dependencies unless explicitly approved and documented in [DECISIONS.md](DECISIONS.md).
- Do not copy source from other torrent clients without verifying license compatibility.
- Prefer documented APIs and official upstream build guidance.
- "Bundled / Distributed" indicates whether the dependency is packaged with the APK or downloaded at runtime.
