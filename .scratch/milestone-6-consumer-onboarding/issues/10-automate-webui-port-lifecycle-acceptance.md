# 10 — Automate WebUI Port lifecycle acceptance

**What to build:** Prove through the real APK and Android UI that WebUI Port changes are atomic, locally recoverable, and isolated from native torrent-session ownership.

**Blocked by:** 02 — Configure the WebUI Port from Android; 08 — Automate the clean-install onboarding happy path.

**Status:** done

- [x] Android shows the default configured and effective port after clean startup.
- [x] Applying a valid available port makes the authenticated WebUI reachable there and retires the old listener.
- [x] An active torrent session and durable transfer intent survive the WebUI server replacement.
- [x] Invalid input, occupied-port bind failure, and forced persistence failure preserve the previous reachable server and configured port.
- [x] A persisted unavailable port on cold start is reported on Android without silently replacing the configuration.
- [x] Browser and WebSocket clients reconnect successfully after a valid switch.
- [x] Teardown removes candidate and active listeners, restores port `8080`, and leaves no daemon, forwarding, credential, queue, or fixture state.
- [x] Existing daemon safe-stop, permission-blocked WebUI, authentication, and WebSocket regressions pass.
