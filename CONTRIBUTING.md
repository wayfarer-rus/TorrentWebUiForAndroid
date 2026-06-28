# Contributing

## Before You Start

1. Read [AGENTS.md](AGENTS.md) — the engineering contract for this project.
2. Read the relevant [skill](skills/) for the area you are working in.
3. Read [ARCHITECTURE.md](ARCHITECTURE.md) for structural conventions.
4. Read [DECISIONS.md](DECISIONS.md) for architectural decisions and rationale.

## Working in This Repository

- **Update docs when architecture changes.** If you change how something works, update the relevant documentation.
- **Keep commits focused.** One logical change per commit.
- **Never claim unrun tests.** Device tests must actually run on a device.
- **Document dependency licenses.** Add every new dependency to [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **Avoid scope creep.** Work within the current milestone's scope. Do not silently add features from future stages.
- **Prefer small reversible changes.** Large refactors should be proposed separately.

## Testing

- Unit tests are encouraged where they add value.
- Real-device validation is required for Android/native/network behavior.
- Record results in [TEST_REPORT.md](TEST_REPORT.md).
- Do not fabricate test results or claim device tests that did not run.

## Questions

When unsure, state your assumptions and propose the smallest change that moves things forward.
