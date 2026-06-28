# Stage 2: Browser Control Proof

## Objective

Prove that a LAN-accessible authenticated WebUI can control the torrent session using the same backend model as the Android UI.

## Goals

- LAN-accessible authenticated WebUI.
- Add magnet via browser.
- List queue with progress.
- Live progress updates.
- Pause, resume, remove from browser.
- Use same backend model as Android UI.
- No raw filesystem browser.
- No USB folder picker in browser.

## Agent Instructions

1. Read [AGENTS.md](../AGENTS.md) and [skills/webui/SKILL.md](../skills/webui/SKILL.md).
2. Read [docs/webui-principles.md](../docs/webui-principles.md).
3. Build on the Stage 1 native engine integration.
4. Implement LAN HTTP server with password authentication.
5. Wire WebUI to the same domain model used by the Android UI.
6. Test from a LAN browser on a separate device.
7. Record results in [TEST_REPORT.md](../TEST_REPORT.md).
8. Do not implement polished design, USB picker, or filesystem browsing.
