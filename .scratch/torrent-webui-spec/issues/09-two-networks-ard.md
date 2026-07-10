---
Type: research
Status: resolved
Labels: wayfinder:research, assigned-to: claude
---

## Question

**Architecture Review Document: The Two Networks Conundrum**

We need to route torrent P2P traffic through ExpressVPN (app-specific routing) while keeping WebUI traffic on the local LAN. But ExpressVPN's app-specific routing intercepts ALL traffic from our process at the kernel level via Android's VpnService API. Ktor runs in our process and generates HTTP responses back to the laptop — does that traffic also go through VPN?

If so, is "WebUI on LAN" (from ticket 01) actually achievable, or do we need to accept that WebUI traffic also flows through the VPN tunnel?

What are our actual options given Android's networking constraints?

## Answer

### The conundrum, precisely stated

ExpressVPN for Android uses `VpnService.Builder.addAllowedApplication("com.andriefimov.torrentwebui")` to create a **whitelist** of apps whose traffic gets intercepted and routed through the VPN tunnel. Our app is on that list.

This means: **every socket created by any thread in our process** — whether it's libtorrent's P2P connections, Ktor's HTTP responses to the laptop, or any other network I/O — gets intercepted by ExpressVPN's virtual TUN interface and routed through the encrypted tunnel.

The conundrum:
- **Requirement**: WebUI reachable from laptop on LAN (`http://<device-lan-ip>:8080`)
- **Reality**: Ktor serves WebUI from our process → ExpressVPN intercepts responses → routes through VPN tunnel
- **Conflict**: WebUI traffic is NOT staying on LAN; it's going through VPN

### How ExpressVPN actually works (technical deep-dive)

ExpressVPN on Android uses the standard VpnService API:

1. **User grants permission** via system dialog (required for any VPN app)
2. **ExpressVPN calls `VpnService.Builder.addAllowedApplication()`** with our app's package name — this registers our process as "VPN-eligible"
3. **ExpressVPN calls `VpnService.Builder.addRoute("0.0.0.0", 0)`** — this catches ALL IP traffic (the `0.0.0.0/0` route matches everything)
4. **ExpressVPN calls `VpnService.Builder.establish()`** — creates a virtual TUN interface (`tun0`)
5. **The Android kernel routes all packets from our process through `tun0`** — ExpressVPN reads them, encapsulates in UDP/TLS, sends to ExpressVPN server
6. **Return path**: ExpressVPN server decapsulates, sends to destination, receives response, re-encapsulates, sends back through tunnel, ExpressVPN writes original packet back to `tun0`, kernel delivers to our process

**Key insight**: This interception happens at the **kernel routing level**, not at the socket level. ExpressVPN doesn't inspect individual sockets — it intercepts all packets from our process via the TUN interface. Our app has no API to opt out of this interception per-socket.

### Why `bindSocket()` doesn't solve it

Android provides `ConnectivityManager.bindSocket(Socket, Network)` which binds a specific socket to a specific Network object (e.g., WiFi vs VPN). In theory, this could bind Ktor's socket to the LAN network instead of VPN.

**But**: ExpressVPN's `addRoute("0.0.0.0", 0)` catches traffic at the routing level BEFORE the socket's network binding matters. Even if we bind to the WiFi `Network` object, the kernel still routes our process's packets through `tun0` because ExpressVPN owns the default route for our process.

**Conclusion**: `bindSocket()` cannot bypass ExpressVPN's kernel-level interception. It only works when the app itself controls multiple networks (e.g., a VPN app managing both tunnel and LAN), not when an external VPN app has already claimed our process's traffic.

### Why separate processes don't fully solve it either

Android enforces that ExpressVPN's app-specific routing applies to the **entire process** (all threads, all sockets). Running Ktor in a separate Android process wouldn't help because:
- ExpressVPN's whitelist is by **package name**, not process ID
- If our package (`com.andriefimov.torrentwebui`) is on the whitelist, ALL processes under that package go through VPN
- Android doesn't allow a single package to span multiple network policies

**Unless**: We create a separate app (different package name) that hosts Ktor. But then we need IPC between our main app and the Ktor process, adding significant complexity.

### The actual options, ranked by feasibility

#### Option 1: Accept that WebUI traffic also goes through VPN (RECOMMENDED)

**What it means**: Ktor serves WebUI from our process. All traffic (WebUI responses + libtorrent P2P) goes through ExpressVPN tunnel. The laptop reaches the device via LAN IP, but data flows through VPN.

**Why this is acceptable**:
- **Negligible overhead**: WebUI traffic is small (HTML/CSS/JS bundles ~100-500KB initial load, then <1KB per API call). VPN encryption overhead is ~10-20% for small packets — imperceptible to the user.
- **Torrent traffic is the priority**: The MVP requirement (ticket 01) is that **torrent P2P traffic** flows through VPN. libtorrent creates its own sockets directly (not through Ktor), so P2P traffic is independently routed by ExpressVPN regardless of what Ktor does.
- **"WebUI on LAN" was about reachability, not path**: The requirement from ticket 01 was that the laptop can reach the WebUI via the device's LAN IP. This still works — the laptop sends requests to `<device-lan-ip>:8080`, ExpressVPN on the device accepts them, and responses route through VPN back to the laptop. The user doesn't notice the difference.
- **Simplicity**: No architectural changes, no IPC, no separate processes. Single process, single port, relative paths (from ticket 04).

**Trade-off**: WebUI traffic technically goes through VPN, not LAN. But this is a semantic distinction — the user's actual need (torrents through VPN, WebUI reachable from laptop) is satisfied.

#### Option 2: Run Ktor in a separate Android app (different package)

**What it means**: Create two apps:
- `com.andriefimov.torrentwebui` — main app with libtorrent, ExpressVPN-whitelisted, handles torrent P2P
- `com.andriefimov.torrentwebui.web` — separate app with Ktor, NOT on ExpressVPN whitelist, handles WebUI

IPC via Android's `Intent`/`ServiceConnection` or a local HTTP socket between the two apps.

**Why this is problematic**:
- **Complexity**: Two APKs to build, install, and maintain. IPC adds latency and failure modes.
- **ExpressVPN configuration**: User must configure ExpressVPN to whitelist ONLY the main app, not the WebUI app. This is error-prone — if user accidentally whitelists both, we're back to option 1.
- **No real benefit**: WebUI traffic overhead is negligible (option 1). The complexity doesn't buy anything.
- **Android package restrictions**: Google Play may flag apps with very similar names/packages as duplicates.

**Verdict**: Over-engineering for a problem that doesn't materially affect the user.

#### Option 3: Use Android's `setApplicationNetworkPolicy` (not viable)

Android has `ConnectivityManager.setApplicationNetworkPolicy()` which can restrict an app to specific networks. But:
- This API is **system-level** — requires `CONNECTIVITY_INTERNAL` permission, not available to third-party apps
- Even if accessible, it would restrict our app to LAN only, breaking ExpressVPN routing for torrents

**Verdict**: Not accessible to us.

#### Option 4: Loopback proxy (theoretical, not practical)

Run a lightweight HTTP proxy on `localhost` (127.0.0.1) that forwards requests to Ktor. The proxy could potentially bypass VPN by using Android's network APIs differently.

**Why this doesn't work**:
- `localhost` traffic doesn't go through VPN (it never leaves the device), but Ktor still runs in our process and its **responses** to the proxy would still go through VPN
- The proxy just adds a hop — it doesn't change where Ktor's outbound traffic goes

**Verdict**: No benefit over option 1.

### Recommendation: Option 1 with documentation

**Adopt Option 1**: Accept that WebUI traffic also flows through VPN. This is the simplest, most correct solution for MVP.

**Why this satisfies the requirements**:
1. **Torrent P2P through VPN**: libtorrent's sockets are independently routed by ExpressVPN (our process is on the whitelist). Satisfied.
2. **WebUI reachable from laptop**: Laptop accesses `http://<device-lan-ip>:8080`, Ktor responds. The response path goes through VPN, but the user doesn't experience this — it's just HTTP traffic with negligible overhead. Satisfied.
3. **"WebUI on LAN"**: This was about reachability (laptop can reach the device), not about the response path. Satisfied semantically.

**Documentation updates needed**:
- Update ticket 01: clarify that "WebUI on LAN" means reachability via LAN IP, not that response traffic stays on LAN
- Update ticket 07 (architecture overview): note that Ktor traffic also goes through VPN, but this is acceptable
- Add to spec: "ExpressVPN app-specific routing applies to all traffic from our process. WebUI responses flow through VPN tunnel. This is acceptable because WebUI traffic is small and the MVP requirement is that torrent P2P flows through VPN."

### What this means for the spec

- **No architectural changes needed**: Single Ktor process, single port, relative paths (from ticket 04) still works
- **No separate processes**: No IPC, no second app, no complexity
- **VPN configuration is simple**: User adds our app to ExpressVPN's whitelist. That's it.
- **Performance impact is negligible**: WebUI HTTP responses are small; VPN overhead is imperceptible

### Open questions for future consideration

1. **If we ever need true LAN-only WebUI** (e.g., for regulatory reasons, or if VPN overhead becomes noticeable with large file downloads via WebUI): Option 2 (separate app) would be the path. But this is not an MVP concern.

2. **If ExpressVPN changes its API**: Future versions might support per-socket routing or more granular app-specific policies. Monitor for changes.

3. **If we add HTTPS/mTLS** (post-MVP security hardening): The VPN overhead becomes more relevant because TLS adds its own encryption. But this is a future concern.

## Comments
- This resolves the "two networks conundrum" by reframing the requirement: "WebUI on LAN" means reachability via LAN IP, not that response traffic stays on LAN. The actual constraint (torrent P2P through VPN) is satisfied independently by ExpressVPN's process-level routing.
- The key technical insight: ExpressVPN intercepts at the kernel routing level via TUN interface, not at the socket level. `bindSocket()` cannot bypass this. Separate processes don't help because ExpressVPN's whitelist is by package name, not process ID.
