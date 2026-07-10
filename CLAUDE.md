# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android app that provides a web UI for qBittorrent and Deluge torrent clients. The native engine (libtorrent via JNI) is a work-in-progress replacement for the HTTP API engines.

## Build, Run & Test Commands

```bash
# Full Gradle build (debug APK)
./gradlew assembleDebug

# Install debug APK on connected device/emulator
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented tests on connected device
./gradlew connectedAndroidTest

# Build and run the app (use the "run" skill for launching from Android Studio)
./gradlew installDebug && adb shell am start -n com.andriefimov.torrentwebui/.MainActivity

# Build native library (requires Android NDK + CMake)
cd app/src/main/jni && cmake --build .

# Sync Gradle project (after adding dependencies)
./gradlew projects

# Clean build
./gradlew clean assembleDebug
```

## Architecture

### Three-tier engine architecture:

1. **HTTP API Engines** (`app/src/main/java/com/andreiefimov/torrentwebui/engine/api/`) — communicate with qBittorrent/Deluge via REST APIs.
   - `BaseTorrentEngine` — abstract base class defining the engine contract (add, remove, pause, resume, get torrents, etc.)
   - `qBittorrentEngine` / `DelugeEngine` — HTTP API implementations

2. **Native Engine** (`app/src/main/java/com/andreiefimov/torrentwebui/engine/native_engine/` + `app/src/main/jni/`) — libtorrent JNI wrapper (Stage 1 in progress).
   - `NativeTorrentEngine` — Kotlin wrapper exposing the same interface as HTTP engines
   - `torrent_jni.cpp` — JNI bridge calling into libtorrent C++ API
   - `TorrentSession.kt` — wraps a single libtorrent session; manages torrents and handles events

3. **Event Bus** (`app/src/main/java/com/andreiefimov/torrentwebui/events/`) — decouples engine events from UI.
   - `TorrentEvent` / `SessionEvent` sealed classes carry typed updates (torrent added/removed, state changed, session started/stopped)
   - `EventBus` is a singleton with `observe()` / `post()` methods used by ViewModels

### UI Layer:
- `MainActivity` — hosts the app, manages engine lifecycle (selects native vs HTTP based on preference)
- `TorrentViewModel` — holds `MutableStateFlow<List<Torrent>>`, subscribes to `EventBus`, exposes data to UI
- `TorrentAdapter` / `TorrentViewHolder` — RecyclerView adapter for torrent list

### Data Model:
- `Torrent.kt` — data class representing a single torrent with state, progress, speed, etc.

### Engine Selection:
- `MainActivity` checks a user preference to decide which engine to instantiate. The native engine requires libtorrent.so loaded via `System.loadLibrary("torrent")`.

## Key Files for Native Engine Work

- `app/src/main/java/com/andreiefimov/torrentwebui/TorrentSession.kt` — Kotlin side of the native session (torrent management, event forwarding)
- `app/src/main/jni/torrent_jni.cpp` — all JNI bridge code (add/remove/pause/resume torrents, fetch info)
- `app/src/main/java/com/andreiefimov/torrentwebui/engine/native_engine/NativeTorrentEngine.kt` — engine interface implementation
- `app/src/main/jni/CMakeLists.txt` — native build config

## Development Notes

- The native engine is **Stage 1** (proof of concept) — not yet production-ready. HTTP API engines remain the default for stable use.
- The project uses Kotlin with coroutines + Flow for async operations.
- Gradle is configured via `build.gradle.kts` (root + app module). The project uses Android Gradle Plugin.
- A `local.properties` file exists (for SDK path) but is not committed — do not add it to git.
- Documentation in `ARCHITECTURE.md`, `DECISIONS.md`, and `ROADMAP.md` provides design context.
- The `skills/` directory contains project-specific Claude Code skills (e.g., `verify`, `run`).

## Commit discipline

Commit after every significant code modification. A "significant" change is one that:
- Adds or modifies a feature, endpoint, or UI component
- Changes the build configuration (Gradle, CMake, dependencies)
- Refactors code that affects behavior visible to the next layer up
- Fixes a bug

Do **not** commit after:
- Purely cosmetic edits (whitespace, formatting) done mid-stream
- Tentative experiments that will be reverted before the ticket is done

Commit message format: `<ticket-number> <brief description>` (e.g., `10 initial ktor server setup with static file serving`).
Keep commits small and atomic — one logical change per commit.

## Agent skills

### Issue tracker

Issues and specs live as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: one `CONTEXT.md` at the repo root + `docs/adr/` for architecture decisions. See `docs/agents/domain.md`.
