# Skill: Testing and Device Validation

Operational guide for testing in this project.

## TEST_REPORT.md Structure

All device tests are recorded in [TEST_REPORT.md](../../TEST_REPORT.md) with:

| Field | Description |
|-------|-------------|
| Environment | Build identity, device, Android version, ABI, network |
| Test case | Name and description |
| Action | What was done |
| Expected result | What should happen |
| Actual result | What actually happened |
| Status | PASS or FAIL |
| Evidence / log reference | Logcat output, screenshots, or file references |

## Test Categories

1. **Unit tests:** Run via Gradle. Fast, no device needed.
2. **Emulator tests:** Useful for UI layout and basic flow validation.
3. **Real-device tests:** Required for native, network, storage, and service behavior.

## Rules

- **Every test must have** environment, action, expected result, actual result, and pass/fail.
- **Failures are valuable** and must be recorded. Do not skip failed tests.
- **No invented screenshots or fake success claims.** Evidence must be real.
- **Do not claim a device test** unless it actually ran on a physical device.
- **Emulator results are not a substitute** for real-device validation of native/network/storage behavior.

## Device Testing Workflow

1. Build and install on target device: `./gradlew installDebug`
2. Run the test scenario manually or via instrumentation.
3. Capture logcat output: `adb logcat -s <tag>`
4. Record results in TEST_REPORT.md.
5. Commit TEST_REPORT.md with the milestone work.
