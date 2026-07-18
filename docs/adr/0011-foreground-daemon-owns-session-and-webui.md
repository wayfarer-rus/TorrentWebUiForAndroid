# Foreground daemon owns torrent-session and WebUI lifecycle

**Status:** Accepted

Milestone 3 makes the foreground Torrent Daemon the sole Android-side owner of the native torrent session and LAN WebUI server. Compose and the WebUI observe and control the daemon through the shared backend model, rather than owning session lifecycle themselves. This replaces Activity/ViewModel ownership so active transfers and browser access can survive `MainActivity` backgrounding; an Android fallback remains limited to daemon health and Start/Stop downloads.

**Considered options:** Keep lifecycle in `MainActivity`/`TorrentViewModel`; move only the native session to the service while retaining the WebUI server in the Activity. Both alternatives make background behavior or browser availability depend on the UI process.
