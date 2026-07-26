# 04 — Gate first use through Onboarding Readiness

**What to build:** Make an unconfigured installation open an authenticated, resumable Consumer Onboarding shell instead of normal torrent controls. The shell reports only consumer-level Onboarding Readiness, while the backend prevents direct authenticated torrent mutations from bypassing setup.

**Blocked by:** None — can start immediately.

**Status:** implemented

- [x] Durable onboarding state is backend-owned and does not rely on browser-local storage.
- [x] Authenticated onboarding status reports completion, Password-decision state, Approved Destination presence, and **Ready**, **Action needed on Android**, or **Service unavailable**.
- [x] The readiness states are exposed only while Ktor is reachable; pre-bootstrap and WebUI bind failures remain Android concerns.
- [x] Before completion, the WebUI renders Consumer Onboarding instead of normal torrent controls and resumes the unresolved step after refresh or restart.
- [x] Authenticated torrent-mutation APIs reject direct calls with `409 onboarding_incomplete` before completion.
- [x] Onboarding-required status, readiness, storage, destination, and Password operations remain usable.
- [x] Static content, APIs, and WebSocket handshakes remain protected by HTTP Basic Authentication.
- [x] Only the first M6 startup without an onboarding marker may migrate an installation with a durable queue, Approved Destination, or non-default Password to completed onboarding with Password choice deferred; unused installations enter onboarding.
- [x] An existing incomplete marker always wins over migration evidence, so an interrupted setup with an Approved Destination remains incomplete.
- [x] Readiness polling uses bounded requests and a modest interval, advances automatically when readiness becomes **Ready**, and never busy-loops or claims to diagnose an unreachable server.
- [x] Integration and browser tests cover one-time migration, incomplete-marker precedence, readiness polling, automatic advancement, API-gate enforcement, authentication, and interruption recovery.
