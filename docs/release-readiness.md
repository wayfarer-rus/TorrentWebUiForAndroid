# Release Readiness Checklist

Use this checklist before considering any release (alpha, beta, or production).

## Functional

- [ ] All current milestone exit criteria met.
- [ ] TEST_REPORT.md updated with real-device results.
- [ ] No known crashes on target device.

## Security

- [ ] WebUI authentication enforced.
- [ ] WebUI binds to LAN interfaces only.
- [ ] No credentials or sensitive data in logs.
- [ ] No debug endpoints exposed.

## Storage

- [ ] Storage permissions acquired and persisted.
- [ ] Permission revocation handled gracefully.
- [ ] USB disconnect/reconnect tested.

## Dependencies

- [ ] All dependencies tracked in THIRD_PARTY_NOTICES.md.
- [ ] No unapproved GPL dependencies.
- [ ] License compatibility verified.

## Documentation

- [ ] AGENTS.md current.
- [ ] ARCHITECTURE.md reflects actual implementation.
- [ ] ROADMAP.md milestones updated.
- [ ] DECISIONS.md includes any new decisions.
- [ ] CONTRIBUTING.md accurate.

## Build

- [ ] Clean build succeeds.
- [ ] No lint errors blocking release.
- [ ] ProGuard/R8 rules configured (if applicable).
