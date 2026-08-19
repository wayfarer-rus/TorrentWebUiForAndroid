# GitHub Publication Readiness

**Prepared:** 2026-08-19
**Target:** public personal repository `wayfarer-rus/TorrentWebUiForAndroid`

This checklist covers public **source repository** publication. It does not declare the Android application production-ready and does not close the physical-device or separate-LAN acceptance gates in [TEST_REPORT.md](../TEST_REPORT.md).

## Completed controls

- [x] Apache-2.0 license and project NOTICE added for original project code.
- [x] Third-party license inventory corrected and expanded; Gradle and npm dependency inventories are locked/generated.
- [x] Required project, libtorrent, Boost, Netty, and Svelte notices verified inside the debug APK.
- [x] README, build instructions, contribution guidance, and security reporting policy updated for public readers.
- [x] GitHub CI, Dependabot, issue forms, pull-request template, and narrowly scoped Gitleaks policy added.
- [x] Generated IDE state and raw emulator logs removed; sanitized evidence moved under `docs/`.
- [x] Local Markdown links and GitHub YAML parsed successfully.
- [x] WebUI checks, policy tests, browser acceptance, JVM tests, lint, APK assembly, npm audit, and full-history Gitleaks scan passed during preparation.

## History/privacy audit

Gitleaks 8.30.1 scanned the complete 82-commit history. Its seven findings were HTTP Basic credentials in three deleted M2 acceptance documents; each value was verified as the documented bootstrap credential or a synthetic test password. `.gitleaks.toml` allows only those exact historical paths. No private key, GitHub/AWS/Google/Slack/Stripe token, credential-bearing database URL, or unresolved Gitleaks finding remained.

Torrent tests and deterministic fixture source necessarily contain synthetic magnet URIs and local/public tracker fixtures. A history-wide classification found no personal workstation path and no private tracker host; localhost tracker fixtures and legal public test torrents remain intentionally versioned. Runtime logging rules still prohibit credentials, magnet URIs, private tracker URLs, and destination paths.

## Remaining release gates

- No supported APK release is published.
- Physical Android-host browser acceptance has not run.
- Separate-LAN desktop-browser acceptance has not run.
- A production release should inspect the final native object map and repeat the binary license/NOTICE audit described in [licensing-publication-review.md](licensing-publication-review.md).
