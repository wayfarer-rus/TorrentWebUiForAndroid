# ADR-010: HTTP Basic Authentication for WebUI

- **Date:** 2026-07-10
- **Status:** Accepted
- **Related Tickets:** webui-auth 01, 02, 03

## Context

The WebUI is accessible on the LAN without any credentials. Any device on the same network can view and control active torrents. A household guest's phone browsing the local network could accidentally discover and interact with downloads.

The threat model is household privacy, not enterprise security. The app runs on a private LAN behind a router NAT. There is no public Internet exposure by default.

## Decision

- Install Ktor `Authentication` plugin with Basic Auth scheme on all routes except `/ws/progress` and `/health`.
- Password stored in Android `SharedPreferences` with key `webui_password`, default value `start123`.
- Username field is ignored; only password is validated.
- Password read from SharedPreferences on every auth check (not cached in memory).
- `constantTimeEquals` used for password comparison to mitigate timing attacks.
- Password change endpoint: `POST /api/settings/password` with body `{currentPassword, newPassword}`.
- New password must be at least 4 characters. No maximum length (passphrases allowed).
- WebSocket endpoint `/ws/progress` bypasses auth — page-level auth is the gate.

## Consequences

**Positive:**
- Simple, standards-based authentication that browsers handle natively.
- Browser renders a native credential dialog — no custom login page needed.
- Password change takes effect immediately (no cache invalidation required).
- Both WebUI and Android app can change the password via the same endpoint logic.

**Negative:**
- Credentials sent in base64 (not encrypted) — acceptable on LAN only, not over public Internet.
- No session management or token-based auth (out of scope for household threat model).
- No rate limiting or brute-force protection (LAN-only, trusted network).
- Password displayed in plain text in Android settings screen (user can hide field later if needed).

**Out of scope:**
- HTTPS/TLS (Milestone 5+ concern).
- Per-user accounts.
- Password reset mechanism (Android app is the fallback).
- Password strength enforcement beyond minimum length.

## References

- Ktor 3.x `ktor-server-auth` module documentation.
- RFC 7617: HTTP Basic Authentication.
- webui-auth spec: `.scratch/webui-auth/spec.md`.
