# 10 — Automate WebUI Port lifecycle acceptance

**What to build:** Prove through the real APK and Android UI that WebUI Port changes are atomic, locally recoverable, and isolated from native torrent-session ownership.

**Blocked by:** 02 — Configure the WebUI Port from Android; 08 — Automate the clean-install onboarding happy path.

**Status:** ready-for-agent

- [ ] Android shows the default configured and effective port after clean startup.
- [ ] Applying a valid available port makes the authenticated WebUI reachable there and retires the old listener.
- [ ] An active torrent session and durable transfer intent survive the WebUI server replacement.
- [ ] Invalid input, occupied-port bind failure, and forced persistence failure preserve the previous reachable server and configured port.
- [ ] A persisted unavailable port on cold start is reported on Android without silently replacing the configuration.
- [ ] Browser and WebSocket clients reconnect successfully after a valid switch.
- [ ] Teardown removes candidate and active listeners, restores port `8080`, and leaves no daemon, forwarding, credential, queue, or fixture state.
- [ ] Existing daemon safe-stop, permission-blocked WebUI, authentication, and WebSocket regressions pass.
