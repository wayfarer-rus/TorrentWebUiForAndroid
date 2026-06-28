# Skill: Android Native

Operational guide for Android-native development in this project.

## Kotlin / Compose Conventions

- Use Kotlin with Jetpack Compose for all Android UI.
- Follow Google's Kotlin style guide (`kotlin.code.style=official`).
- ViewModels for UI state; no business logic in composables.
- Use `remember` and `derivedStateOf` appropriately.
- Prefer `Material3` components.

## Gradle

- Use version catalogs (`libs.versions.toml`) for all dependencies.
- Pin versions explicitly; do not use floating versions in production.
- Keep Gradle wrapper up to date with official releases.
- Use `android.application` plugin type for the app module.

## SDK / NDK / CMake Discovery

- SDK path from `local.properties` (not committed).
- NDK path from `local.properties` or Android Studio bundled NDK.
- CMake from NDK bundle or Android SDK CMake.
- Do not hardcode SDK/NDK paths in build scripts.

## Target ABI

- **arm64-v8a first.** This is the primary and initial target ABI.
- Additional ABIs (armeabi-v7a, x86_64) may be added later if needed.
- Configure `ndk.abiFilters` in `build.gradle.kts`.

## Permissions

- Avoid unnecessary Android permissions.
- Request only the permissions needed for the current milestone.
- Document each permission's purpose in `AndroidManifest.xml` comments.
- Prefer runtime permissions over manifest-only where applicable.

## Device Install / Test Expectations

- Test on a real device whenever possible.
- Use `adb install -r` for iterative testing.
- Use `adb logcat` for debugging; filter by package name.
- Record device test results in [TEST_REPORT.md](../../TEST_REPORT.md).
- Do not claim device test results without actually running on a device.
