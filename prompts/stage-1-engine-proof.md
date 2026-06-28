# Stage 1: Native Engine Proof

## Objective

Prove that our own Android app can embed libtorrent-rasterbar and manage a real torrent session safely.

## Required Deliverable

- Build and run on arm64-v8a Android 13.
- Bundle libtorrent-rasterbar through Android NDK/CMake.
- Expose a minimal Kotlin-facing JNI bridge.
- Create one libtorrent session.
- Let user paste one magnet URI.
- Add magnet to the session.
- Download into app-private external storage only.
- Display torrent name, state, progress, download rate, upload rate, peers, save location.
- Support add, pause, resume, remove, and delete files.
- Include diagnostics: ABI, libtorrent version, native load result, session startup result, last error.
- Validate using an Ubuntu torrent only.

## Out of Scope

- No WebUI.
- No USB/SAF integration.
- No VPN integration.
- No RSS.
- No categories.
- No boot restore.
- No polished UX.

## Agent Instructions

1. Read [AGENTS.md](../AGENTS.md) and [skills/native-libtorrent/SKILL.md](../skills/native-libtorrent/SKILL.md).
2. Read [skills/android-native/SKILL.md](../skills/android-native/SKILL.md).
3. Inspect the existing Android scaffold before making changes.
4. Pin the libtorrent version/commit and record it in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
5. Implement the minimal JNI bridge and native session.
6. Build, install, and test on a real arm64-v8a device.
7. Record results in [TEST_REPORT.md](../TEST_REPORT.md).
8. Do not implement features outside this milestone's scope.
