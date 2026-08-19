# Contributing

Thanks for helping improve TorrentWebUiForAndroid.

## Before you start

1. Read [AGENTS.md](AGENTS.md), the repository engineering contract.
2. Review [ARCHITECTURE.md](ARCHITECTURE.md) and [DECISIONS.md](DECISIONS.md).
3. Search existing issues before opening a proposal or implementation.
4. For security vulnerabilities, follow [SECURITY.md](SECURITY.md) instead of filing a public issue.

## Development setup

Follow [BUILD_AND_RUN.md](BUILD_AND_RUN.md). Clone submodules, bootstrap pinned native dependencies, and install WebUI packages with `npm ci`.

## Change guidelines

- Keep each commit focused and prefer small, reversible changes.
- Preserve the browser-first product and the shared backend/domain model.
- Do not add public Internet exposure, router configuration, VPN inspection, synthetic storage paths, or device-specific assumptions.
- Update architecture, ADRs, roadmap, and test evidence when behavior or boundaries change.
- Record every new or upgraded dependency and its license in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- Never commit real user credentials, authorization headers, private tracker URLs, personal filesystem paths, or unredacted diagnostics. Synthetic magnets and localhost/public tracker fixtures belong only in deterministic test source and must never be emitted by runtime logs.

## Validation

Run the smallest relevant checks and report exactly what ran:

```bash
cd web
npm run test:ci

cd ..
./gradlew :app:testDebugUnitTest :app:lintDebug --console=plain
```

Android instrumentation, native networking, storage, and LAN behavior require emulator or device-specific validation. Do not describe emulator results as physical-device results. Record acceptance evidence in [TEST_REPORT.md](TEST_REPORT.md) when relevant.

## Pull requests

A pull request should explain:

- the user-visible or architectural problem;
- the smallest implemented solution;
- commands and environments actually validated;
- security, storage, licensing, and compatibility risks; and
- remaining limitations or follow-up work.

By contributing, you agree that your original contribution is licensed under this repository's [Apache License 2.0](LICENSE). Do not submit third-party code unless its license and provenance are documented and compatible.
