# Third-Party Notices

Original TorrentWebUiForAndroid code is licensed under Apache License 2.0. Third-party components are not relicensed by this project and remain subject to their upstream licenses.

Versions below are declared or resolved by the current Gradle and npm lockfiles. “Bundled” means code is distributed in the APK; build/test-only tools are not shipped as application runtime code. Resolved app dependencies for Gradle's lockable configurations are pinned in [`app/gradle.lockfile`](app/gradle.lockfile); plugin/tool distributions remain pinned by their declared versions and wrapper checksum. The complete generated npm inventory, including all 110 resolved direct, transitive, optional-platform, build, and test packages with versions and SPDX license identifiers, is tracked in [`third_party/web-dependencies.json`](third_party/web-dependencies.json) and checked by CI.

## Bundled runtime components

| Dependency | Version / Commit | Source | License | Distribution notes |
|---|---:|---|---|---|
| libtorrent-rasterbar | v2.0.10 (`74bc93a37`) | https://github.com/arvidn/libtorrent | BSD-3-Clause, with additional per-file licenses documented upstream | Statically linked into the JNI shared library. The complete pinned `libtorrent/LICENSE` is packaged with the APK. The submodule includes separately licensed files, including an APSL-2.0 route header; the Android build does not intentionally compile Apple route code. |
| Boost headers | 1.86.0 | https://www.boost.org | Boost Software License 1.0 | Used by libtorrent; bootstrapped from a SHA-256-pinned archive. The license is packaged with the APK. |
| AndroidX Core KTX | declared 1.15.0; resolved 1.16.0 | https://github.com/androidx/androidx | Apache-2.0 | Bundled Android runtime. |
| AndroidX AppCompat | 1.7.1 | https://github.com/androidx/androidx | Apache-2.0 | Bundled Android runtime. |
| Material Components for Android | 1.14.0 | https://github.com/material-components/material-components-android | Apache-2.0 | Bundled Android runtime. |
| Jetpack Compose | BOM 2025.03.01; UI 1.7.8; Material3 1.3.1 | https://github.com/androidx/androidx | Apache-2.0 | Bundled Android UI runtime. |
| AndroidX Lifecycle | 2.8.7 | https://github.com/androidx/androidx | Apache-2.0 | Bundled Android runtime. |
| AndroidX Activity Compose | 1.10.1 | https://github.com/androidx/androidx | Apache-2.0 | Bundled Android runtime. |
| Kotlin standard library | resolved 2.2.10 | https://github.com/JetBrains/kotlin | Apache-2.0 | Bundled Kotlin runtime. |
| kotlinx.coroutines | resolved 1.9.0 | https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0 | Bundled coroutine runtime. |
| Ktor server | 3.0.1 | https://github.com/ktorio/ktor | Apache-2.0 | Bundled embedded HTTP/WebSocket server. |
| Netty | 4.1.114.Final | https://github.com/netty/netty | Apache-2.0 | Bundled Ktor engine/runtime; upstream NOTICE is packaged with the APK. |
| kotlinx.serialization JSON | 1.7.3 | https://github.com/Kotlin/kotlinx.serialization | Apache-2.0 | Bundled JSON runtime. |
| Svelte | 5.56.9 | https://github.com/sveltejs/svelte | MIT | Compiled WebUI runtime bundled in APK assets; pinned upstream license is packaged with the APK. |

## Build and test components

| Dependency | Version | Source | License | Use |
|---|---:|---|---|---|
| Android Gradle Plugin | 9.2.1 | https://android.googlesource.com/platform/tools/base/ | Apache-2.0 | Build toolchain. |
| Gradle | 9.4.1 | https://github.com/gradle/gradle | Apache-2.0 | Build toolchain/wrapper. |
| Kotlin Compose and serialization plugins | 2.1.20 | https://github.com/JetBrains/kotlin | Apache-2.0 | Build plugins. |
| Foojay Toolchains Resolver | 1.0.0 | https://github.com/gradle/foojay-toolchains | Apache-2.0 | Gradle toolchain resolver. |
| Android SDK / NDK | SDK 36.1 / NDK 29.0.14206865 | https://developer.android.com | Android SDK License | Build toolchain. |
| CMake | 3.22.1 | https://cmake.org | BSD-3-Clause | Native build toolchain. |
| JUnit 4 | 4.13.2 | https://github.com/junit-team/junit4 | EPL-1.0 | JVM tests only. |
| AndroidX Test / Espresso / Compose UI Test | 1.3.0 / 3.7.0 / Compose BOM 2025.03.01 | https://github.com/android/android-test and https://github.com/androidx/androidx | Apache-2.0 | Instrumentation tests only. |
| OkHttp | 4.12.0 | https://github.com/square/okhttp | Apache-2.0 | Instrumentation tests only. |
| Playwright Test | 1.61.1 | https://github.com/microsoft/playwright | Apache-2.0 | Browser acceptance tests only. |
| SvelteKit / adapter-static | 2.70.3 / 3.0.10 | https://github.com/sveltejs/kit | MIT | WebUI build toolchain. SvelteKit's transitive `cookie` dependency is overridden to patched 0.7.2. |
| Svelte Vite plugin | 5.1.1 | https://github.com/sveltejs/vite-plugin-svelte | MIT | WebUI build toolchain. |
| Vite | 6.4.3 | https://github.com/vitejs/vite | MIT, with bundled third-party notices in its upstream license | WebUI build toolchain. |
| svelte-check | 4.7.2 | https://github.com/sveltejs/language-tools | MIT | Static analysis. |
| TypeScript | 5.9.3 | https://github.com/microsoft/TypeScript | Apache-2.0 | Type checking/build toolchain. |

## License delivery

Android builds generate an `open_source_licenses/` assets directory containing:

- this notice inventory and the resolved Gradle/npm dependency inventories;
- the project's Apache-2.0 license and NOTICE;
- the complete pinned libtorrent license (including its per-file license disclosures);
- the Boost Software License 1.0 text;
- Netty's pinned NOTICE; and
- Svelte's pinned MIT license; and
- the complete generated WebUI dependency inventory.

Source distributions also preserve upstream licenses in the `libtorrent/` submodule and `third_party/notices/`.

## Compatibility notes

- No GPL or LGPL dependency is declared in the Android or WebUI build.
- libtorrent is primarily BSD-3-Clause, not Boost-licensed as a whole. Static linking does not impose copyleft terms, but its binary notice requirements still apply.
- The libtorrent source tree contains separately licensed files. Project Apache-2.0 terms apply only to original project code and do not replace those licenses.
- JUnit 4 is EPL-1.0 and test-only; it is not distributed in the APK.

## Contributor rule

Before adding or upgrading a dependency, update this file, preserve required license/NOTICE material, and verify that generated APK notices remain complete. Avoid GPL dependencies unless explicitly approved and documented in [DECISIONS.md](DECISIONS.md).

This inventory is an engineering compliance record, not legal advice.
