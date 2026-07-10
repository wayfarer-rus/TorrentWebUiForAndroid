---
Type: grilling
Status: resolved
Labels: wayfinder:grilling, assigned-to: claude
---

## Question

How should the spec document be structured? What's the deliverable shape?

The destination is "a spec" — but what does that look like as a concrete artifact? Options:

- **Architecture design doc** — system overview, component diagrams, data flow, API contracts. Ready for an engineer to implement from.
- **Technical specification** — detailed enough that multiple engineers could work on different pieces simultaneously. Includes interface definitions, error handling, edge cases.
- **Hybrid** — high-level architecture with deep dives on the hardest decisions (libtorrent session model, WebSocket protocol, storage abstraction).

What depth and structure should the spec have? Who is the intended reader (the engineer implementing it, a future maintainer, ourselves for reference)?

## Answer

**Hybrid format, no code examples (to avoid drift):**

- `docs/spec/architecture.md` — components, data flow, threading model
- `docs/spec/api.md` — REST endpoint contracts + WebSocket events (interface-level, no implementation)
- `docs/spec/implementation.md` — build config, known pitfalls, storage handling notes
- `docs/spec/rollout.md` — phased rollout plan with acceptance criteria per phase

**Reader:** the engineer implementing it + future maintainer.

**Rule:** spec describes *what*, not *how*. Implementation details live in code, not docs.

## Comments
