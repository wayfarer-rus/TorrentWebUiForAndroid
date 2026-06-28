# Skill: Native libtorrent

Operational guide for integrating libtorrent-rasterbar as the native torrent engine.

## JNI Isolation

- The native engine **must remain isolated behind JNI**.
- Kotlin never directly owns libtorrent native objects.
- The native layer owns session lifecycle (create, destroy, pause, resume).
- JNI exchanges small, typed DTO-like models. No raw pointers, no giant JSON blobs.

## Version Pinning

- Pin the exact libtorrent version or commit hash used.
- Record in [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).
- Do not upgrade versions without documenting the reason and testing the impact.

## Native Build Flags

- Document all CMake/NDK build flags used.
- Typical flags:
  - `-std=c++17` or later as required by libtorrent.
  - Boost integration (bundled or system).
  - OpenSSL for HTTPS tracker support (optional, document if enabled).
  - `-fPIC` for shared library compatibility.
- Do not enable unnecessary libtorrent features.

## Lifecycle Safety

- Native session must be created and destroyed explicitly.
- No dangling native references after Kotlin object disposal.
- Use `extern "C"` for JNI entry points to avoid name mangling issues.
- Handle native exceptions before they cross the JNI boundary.

## Validation Checklist

Before claiming native engine integration is complete:

- [ ] Real metadata retrieval works (not just session creation).
- [ ] Pause and resume work correctly.
- [ ] Remove torrent works (with and without deleting files).
- [ ] Error handling surfaces structured errors to Kotlin, not crashes.
- [ ] Diagnostics show valid ABI, libtorrent version, and session state.

## Feature Discipline

- Do not add unsupported torrent features prematurely (RSS, DHT scraping, IP filtering, etc.).
- Implement only what the current milestone requires.
- Future features should be evaluated against the product principles in [AGENTS.md](../../AGENTS.md).
