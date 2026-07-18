# AGENTS.md — Engineering Contract

This file is the primary authority for how agents and contributors work in this repository. Read it before making edits.

## Product Principles

- **Browser-first product.** The main user experience is a modern LAN-accessible WebUI.
- **Android UI is onboarding, permissions, service health, and fallback control.** The native app exists to bootstrap permissions, manage service lifecycle, and provide emergency overrides.
- **Consumer defaults first.** Advanced torrent settings are hidden by default. The default experience targets a non-technical household user.
- **Do not expose raw Android paths in the WebUI.** Storage destinations must be represented as human-readable, app-approved logical locations.
- **No Note 20-specific product strings, hostnames, or assumptions.** The app is device-agnostic.
- **No dependency on root, Termux, private LAN naming, or a specific VPN provider.** VPN split tunneling is external deployment configuration, not app logic.

## Architecture Rules

- **Kotlin + Jetpack Compose** for Android UI.
- Future native torrent engine must be behind a **narrow JNI boundary**.
- **Kotlin must never directly own libtorrent native objects.** The native layer owns native session lifecycle.
- JNI must exchange **small typed DTO-like models**, not raw pointers or giant JSON blobs.
- WebUI and Android UI must consume the **same backend/domain model**.
- WebUI must not invent separate storage logic from Android.
- Avoid duplicate configuration surfaces. Settings defined once, consumed everywhere.

## Security Rules

- WebUI **LAN-only by default**.
- WebUI **authentication required by default** (password).
- **No public Internet exposure features** by default.
- **No router port-forwarding guidance** inside the app.
- **Do not attempt to inspect or control another VPN app.** Treat VPN routing as external configuration.
- **Do not log** credentials, tokens, magnet URIs, private tracker URLs, or personal paths unnecessarily.
- **Prefer local-only operation** with no cloud dependency.

## Storage Rules

- Future shared/external storage access must use **Android-supported permission models (SAF)**.
- Never pretend a browser WebUI can browse arbitrary Android filesystem paths.
- WebUI destination selection must use **app-approved logical locations**.
- USB disconnect/reconnect and revoked permissions must be treated as **normal recoverable states**.
- App-private storage is acceptable only for proof-of-concept stages.

## Licensing Rules

- **Track every third-party dependency.** Record license and source in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **Avoid GPL dependencies** unless explicitly approved and documented.
- **Do not copy source from other torrent clients** without checking licensing implications.
- Prefer documented APIs and official upstream build guidance.

## Quality Rules

- Every milestone must have **explicit exit criteria**.
- **Do not claim success because code compiles.** Real-device validation is required for Android/native/network behavior.
- Device validation belongs in [TEST_REPORT.md](TEST_REPORT.md).
- Keep [ARCHITECTURE.md](ARCHITECTURE.md), [ROADMAP.md](ROADMAP.md), [DECISIONS.md](DECISIONS.md), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) current when relevant changes are made.
- Add tests where realistic, but **prioritize real-device validation** for Android/native/network behavior.
- **Do not silently broaden scope** into future stages.

## Agent Workflow Rules

1. **Read AGENTS.md and relevant skills** before editing code or architecture.
2. **Inspect existing implementation** before proposing structural changes.
3. **State assumptions** before major architecture decisions.
4. **Prefer small reversible changes.**
5. **Keep commits focused.** One logical change per commit.
6. **When blocked, document evidence** and the smallest next experiment.
7. **Do not fabricate test results.**
8. **Do not claim a device test** unless it actually ran on a physical device.
9. **Before adding dependencies**, explain why they are needed and document their license.
10. **After implementing work, run /code-review before committing.** The implement skill requires it; AGENTS.md enforces it in persistent context.

## Agent Workflow and Timeout Rules

- **Assume a hard request timeout of 300 seconds.** Plan work accordingly.
- **Work in small, resumable batches.** Split substantial work into numbered batches.
- **Keep each batch independently reviewable** and ideally independently committable.
- **Prefer focused patches** over broad rewrites.
- **Avoid generating or printing large files in full.**
- **Keep reports under ~1000 tokens** unless debugging requires more.
- **Stop after a completed batch** if further work risks timeout.
- **After each batch:** summarize changes, list files touched, run the smallest relevant validation, state the exact next batch.
- **If more work remains,** stop after the completed batch and wait for the next instruction.
