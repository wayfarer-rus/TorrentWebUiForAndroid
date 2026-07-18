# Manual Recovery for Unavailable Destinations

Milestone 4 treats a disconnected USB destination or revoked SAF permission as unavailable and pauses its affected torrents. The app revalidates destinations when the app or daemon starts, but the user must explicitly resume after reconnection or reauthorization; continuous reconnection detection, downloaded-data verification, and automatic resume are deferred to Milestone 9 to keep the initial SAF integration narrow and safe.
