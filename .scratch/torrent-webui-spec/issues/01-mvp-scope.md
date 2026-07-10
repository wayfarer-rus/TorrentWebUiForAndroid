---
Type: grilling
Status: resolved
Labels: wayfinder:grilling, assigned-to: claude
---

## Question

What does MVP look like vs full feature set for a Transmission-like WebUI torrent client?

We need to define two tiers:
- **MVP**: the smallest useful product — add torrents, see them download, basic controls. Ship this first to validate the architecture.
- **Full feature set**: everything Transmission offers — selective file download, tracker management, categories/rationalizer, bandwidth limits, search/filter.

The spec should describe both tiers clearly so implementation can proceed in phases. We may hit unsolvable problems along the way, so MVP should be achievable even if full features prove difficult.

What goes in MVP? What can wait?

## Answer

**MVP = Core torrent management + correct networking boundary:**

- Add torrent via magnet link or URL
- Torrent list with status, progress, speeds
- Pause / resume / delete controls

**MVP networking constraints (non-negotiable):**
1. Torrent traffic flows through ExpressVPN (user configures app-specific routing in ExpressVPN)
2. WebUI traffic flows to LAN only (Ktor binds `0.0.0.0`, accessible from laptop via `http://<device-lan-ip>:<port>`)

**MVP acceptance criteria:**
- User adds a magnet link via WebUI → torrent downloads through ExpressVPN tunnel
- WebUI reachable from laptop on LAN
- Pause / resume / delete work end-to-end
- Live progress/speed updates in torrent list

**Full feature set (post-MVP):** file upload, selective file download, tracker management, categories/rationalizer, search/filter, bandwidth settings page.

## Comments

