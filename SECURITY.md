# Security Policy

## Supported versions

This repository is pre-release. Only the current default branch receives security fixes; no released version is currently supported.

## Reporting a vulnerability

Use GitHub's **Report a vulnerability** feature in the repository Security tab. Please do not disclose a vulnerability in a public issue before a fix is available.

Include the affected commit/version, Android version and device class, reproduction steps, impact, and the smallest useful diagnostic evidence. Redact passwords, authorization headers, magnet URIs, private tracker URLs, tokens, and personal filesystem paths.

If private vulnerability reporting is unavailable, open a public issue containing no sensitive details and ask the maintainer for a private contact channel.

## Security boundaries

- The WebUI is intended for trusted LAN access, not direct Internet exposure.
- Authentication is required by default. Users should replace the bootstrap password during onboarding.
- The app does not configure routers, inspect other VPN apps, or provide public exposure features.
- Download destinations are backend-validated canonical paths under approved Android storage volumes.
- The app has no telemetry or cloud dependency by default.

See [docs/security-model.md](docs/security-model.md) for the project threat model.
