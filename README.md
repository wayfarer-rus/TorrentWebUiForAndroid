# TorrentWebUiForAndroid

A browser-first torrent downloader for Android. The Android app handles onboarding, permissions, and service management. The primary user interface is a LAN-accessible WebUI.

## Current Stage

**Pre-Stage 1** — Repository operating system established. No implementation yet.

## Roadmap

See [ROADMAP.md](ROADMAP.md) for milestones and exit criteria.

## How to Work in This Repository

1. Read [AGENTS.md](AGENTS.md) — the engineering contract for all contributors and agents.
2. Read the relevant [skill](skills/) for the area you are working in.
3. Read [ARCHITECTURE.md](ARCHITECTURE.md) for structural conventions.
4. Read [DECISIONS.md](DECISIONS.md) for architectural decisions and rationale.
5. Follow the milestone prompts in [prompts/](prompts/) when starting a stage.

## Directory Structure

```
/
  AGENTS.md                 # Engineering contract (read first)
  ARCHITECTURE.md           # Architecture overview and conventions
  ROADMAP.md                # Milestones and exit criteria
  DECISIONS.md              # Architecture decision records
  CONTRIBUTING.md           # Contributor guidelines
  TEST_REPORT.md            # Device test results template
  THIRD_PARTY_NOTICES.md    # Dependency license tracking
  app/                      # Android application module
  docs/                     # Detailed design documents
  skills/                   # Agent operational guides
  prompts/                  # Milestone implementation prompts
  scripts/                  # Build and utility scripts
```

## Product Principles

- Browser-first: the WebUI is the main product experience.
- Android app: onboarding, permissions, service health, emergency controls.
- Consumer-friendly defaults; advanced settings hidden.
- LAN-only by default; authenticated access.
- Device-agnostic; no root, Termux, or VPN dependencies.
