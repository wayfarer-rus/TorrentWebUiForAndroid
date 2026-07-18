# M2 Emulator Partial Acceptance

## Objective

Produce evidence that the authenticated WebUI and Android UI work together on an Android emulator, while preserving the separate physical-device LAN gate required for full Milestone 2 acceptance.

## Scope

- Minimal headless UI tooling investigation and installation when genuinely required.
- Emulator-local Android UI, HTTP Basic Authentication, WebUI settings, and torrent lifecycle validation.
- Official Arch Linux torrent fixture with bounded transfer and retry behavior.
- Accurate partial-acceptance reporting and deterministic cleanup.

## Boundaries

- Emulator evidence does not establish reachability from another LAN device.
- Physical-device LAN acceptance remains an independent M2 requirement.
- Test passwords are ephemeral, never reported, and restored to the documented default after testing.
- A torrent fixture that cannot reach the agreed state transitions is reported as a network/test-fixture blocker, never a pass.
