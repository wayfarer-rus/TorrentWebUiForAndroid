# Milestone 3: Persistent Daemon

**Status:** Approved specification  
**Primary outcome:** A foreground Android daemon owns the torrent session and LAN WebUI independently of `MainActivity`, while retaining consumer-safe controls and deterministic emulator acceptance.

## Objective

Keep the torrent engine and authenticated LAN WebUI available while `MainActivity` is backgrounded or the transfer queue is idle. After ordinary Android system termination, restore the queue when the user next opens the app. This milestone is a browser-first lifecycle change: Android becomes onboarding, health, and emergency Start/Stop only.

## Sources of truth

- [ROADMAP.md](../ROADMAP.md) — current milestone and product boundaries.
- [CONTEXT.md](../CONTEXT.md) — canonical lifecycle, recovery, notification, and acceptance terminology.
- [ADR-0011](../docs/adr/0011-foreground-daemon-owns-session-and-webui.md) — daemon ownership decision.
- [AGENTS.md](../AGENTS.md) — security, validation, licensing, and batch rules.

If this specification conflicts with a listed source of truth, stop and resolve the conflict before implementation.

## Scope

### In scope

1. A single `TorrentDaemon` foreground service is the only Android-side lifecycle owner of:
   - the native libtorrent session; and
   - the Ktor WebUI server.
2. `MainActivity` can start or stop the daemon and display non-sensitive daemon health, but does not duplicate queue or per-torrent controls.
3. The WebUI remains reachable on the LAN while the daemon is active, even when `MainActivity` is backgrounded and when the queue is idle.
4. App-private recovery records preserve the queue and native resume state across ordinary system termination.
5. A visible, privacy-safe foreground notification represents daemon state and provides the sole notification action: **Stop downloads**.
6. Fully automated emulator end-to-end acceptance with the real JNI/libtorrent session and a deterministic local torrent fixture.

### Explicitly out of scope

- Boot receiver, reboot recovery, or automatic startup after device reboot.
- SAF destinations, USB picker, raw-path browser, or WebUI filesystem access.
- Physical-device or separate-LAN-browser acceptance. This remains required before claiming real LAN accessibility, but is deferred from M3.
- Automatic storage-reconnection detection or downloaded-data verification/recheck. Those belong to Milestone 9.
- Android queue list, individual torrent controls, or a second day-to-day control surface.
- TLS/HTTPS, VPN integration, router configuration, RSS, categories, or polished WebUI redesign.
- New third-party libraries unless separately justified and recorded in `THIRD_PARTY_NOTICES.md` before addition.

## Canonical behavior

### Ownership and lifecycle

- `TorrentDaemon` owns session initialization, polling/alert processing, session destruction, Ktor start, and Ktor stop.
- `TorrentViewModel` and `MainActivity` must not initialize or destroy `TorrentSession` and must not own `TorrentServer` lifecycle.
- Compose and Ktor use one shared daemon-facing backend/domain model. Kotlin continues to interact with the native layer only through typed JNI-facing models; it never owns native libtorrent objects or pointers.
- The daemon begins when the user explicitly starts downloads from Android fallback control, or when the user next opens the app after ordinary system termination while durable enabled intent exists. It remains active while downloads are active and after the queue becomes idle, so the WebUI remains available.
- The only normal daemon shutdown is the user’s explicit **Stop downloads** action. The Android fallback provides the equivalent Start/Stop control.

### Lifecycle states

| State | Meaning | Allowed transition |
|---|---|---|
| `Stopped` | No foreground service, native session, or WebUI server is running. | User starts downloads → `Starting`. |
| `Starting` | Service is creating/restoring the session and starting the WebUI server. | Success → `Running`; failure → `Stopped` with a recoverable health error. |
| `Running` | Foreground service, native session, and WebUI server are live. Queue may be active or idle. | Stop → `Stopping`; ordinary system termination → recovery on next app launch. |
| `Stopping` | Explicit safe-stop checkpoint is being written. | Checkpoint success → `Stopped`; timeout/failure → `Running` with recoverable error. |
| `RecoveryBlocked` | A record cannot safely resume (for example, rejected resume data or unavailable storage). The daemon may otherwise remain `Running`. | User retry/remove, or Milestone 9 storage recovery. |

`RecoveryBlocked` is an entry-level condition, not a second daemon process.

### Ordinary termination, explicit stop, and force stop

- **System termination:** If Android terminates the process without an explicit stop, the user’s next app launch starts the daemon and restores eligible queue entries.
- **Explicit stop:** Stop is durable intent. It stops the foreground service and WebUI server only after a successful safe-stop checkpoint.
- **Android Force stop:** Treat as explicit stop. Do not automatically recover; the user must explicitly use Start downloads later.
- **Start downloads:** Resume every *healthy unfinished* queue entry, including entries individually paused before the explicit stop. Entries in a recoverable-error state remain paused.
- **Reboot:** No M3 recovery behavior is promised.

### Safe-stop durability

1. Queue mutations (add, pause, resume, remove, daemon enabled/disabled intent) are made durable before reporting success to the initiating control surface.
2. Native resume data is checkpointed with atomic replacement no less frequently than once every 30 seconds while the daemon is running.
3. **Stop downloads** synchronously writes queue intent and the latest resume checkpoint with a five-second deadline.
4. If safe-stop persistence fails or exceeds the deadline, the daemon remains running and reports a recoverable error. It must not claim a successful stop.
5. Following unplanned system termination, at most 30 seconds of transfer progress may be lost. The queue and a previously valid recovery record must not be lost or corrupted.

### Recovery records

Recovery records live only in app-private storage. A record contains the minimum typed queue metadata required to restore an entry, its intended state, and opaque native resume data.

- Do not expose recovery-record contents through WebUI responses, diagnostics, notification text, or logs.
- Do not log magnet URIs, private tracker URLs, tokens, or private paths.
- A corrupt or native-rejected resume payload restores safe queue metadata as a paused `RecoveryBlocked` entry with a non-sensitive recoverable error. Do not crash, silently discard it, or retry it in a background loop.
- A native startup/restoration failure stops the daemon cleanly and exposes a non-sensitive recoverable health error. Only a user Start action retries it.

### Storage interruption

Unexpected storage unavailability is not an ordinary lifecycle restart.

- In M3, pause affected entries and surface a recoverable error.
- Do not resume directly merely because the process or service restarts.
- Automatic reconnection detection and downloaded-data verification before resuming are explicitly deferred to M9.

### WebUI and Android control surfaces

- Keep the existing authenticated WebUI queue endpoints and shared status model. Route their operations through the daemon-owned backend instead of Activity/ViewModel-owned session state.
- Add a daemon health representation to the existing health/status surface: daemon lifecycle state, whether recovery is blocked, and a non-sensitive last recoverable error. Do not disclose sensitive record contents.
- Expose authenticated **Stop downloads** in the WebUI while the daemon is running. Its semantics are exactly the safe-stop contract above. Once stopped, the WebUI server is unavailable; restarting requires Android fallback control.
- Android fallback exposes only daemon health and Start/Stop downloads. It must not add or preserve a native queue list or per-torrent controls as a competing daily UI.
- Moving `TorrentServer` lifecycle into the daemon must preserve M2 authentication behavior. It must not weaken LAN-only or password-required defaults.

### Foreground notification

- Declare and request the Android permissions needed for a foreground service and Android 13+ notification visibility.
- On Android 13+, notification permission is a prerequisite to starting the daemon. If denied, explain the requirement through Android fallback UI and leave the daemon stopped.
- Notification content is aggregate and non-sensitive only: active-torrent count, overall progress, and aggregate transfer rate.
- Never show torrent names, magnet URIs, tracker data, save paths, or raw Android paths in notification content, including on the lock screen.
- The notification has one action, **Stop downloads**. It invokes the same safe-stop contract as WebUI and Android fallback. Individual pause/resume/remove remain WebUI operations.

## Required implementation seams

The implementation must remain narrow and reversible.

| Current seam | Required M3 direction |
|---|---|
| `TorrentViewModel` initializes, polls, and destroys `TorrentSession` | Move session ownership/polling to the daemon; ViewModel observes daemon state only. |
| `MainActivity` creates auth manager and starts `TorrentServer` | Daemon starts/stops Ktor using application-scoped authentication and backend dependencies. |
| `TorrentServer` receives `TorrentSessionOps` | Preserve this testing seam; bind it to a daemon-owned operation adapter rather than direct UI lifecycle ownership. |
| `AndroidManifest.xml` has no service/foreground/notification declarations | Add only the platform service and permissions required by M3. |
| Existing WebUI Ktor routes | Preserve authenticated M2 behavior while adding daemon health and Start/Stop controls. |

Do not broaden the JNI surface unnecessarily. If native resume-data access cannot be represented by small typed Kotlin DTOs plus opaque byte/string payloads, stop and propose a narrower interface before coding.

## Acceptance specification

### Automated emulator end-to-end environment

- Use an installed app on an Android emulator, not a JVM-only service simulation.
- Use the real `libtorrent-jni` path and a deterministic local torrent fixture. Fakes may supplement unit tests but cannot satisfy E2E acceptance.
- Keep every command/test batch within the repository’s 300-second hard request limit. Split longer work into independent, reviewable batches.
- The test fixture must be bounded in size and transfer duration. It must not depend on a public swarm or unstable Internet availability.

### Required E2E scenarios

1. **Foreground start and reachability:** grant notification permission, start downloads, confirm the foreground daemon reaches `Running`, Ktor responds from the emulator-local host path, and the notification contains aggregate non-sensitive state.
2. **Background continuity:** background `MainActivity`; verify the daemon, real native transfer, and emulator-local WebUI continue without Activity ownership.
3. **Idle continuity:** let the queue become idle; verify the daemon and WebUI remain available until explicit stop.
4. **Safe stop/start:** stop downloads; assert the service/server stop only after a valid checkpoint. Start again; assert all healthy unfinished entries resume and recovery-blocked entries remain paused.
5. **Ordinary system termination:** induce an emulator-appropriate non-Force-stop process termination; launch the app; verify eligible queue restoration and no more than the defined 30-second resume checkpoint gap.
6. **Force-stop semantics:** force-stop the app; verify no automatic recovery. Verify later explicit Start is required.
7. **Corrupt/rejected recovery record:** inject a controlled invalid record; verify no crash, a paused recoverable-error entry, and no background retry loop.
8. **Storage unavailable boundary:** simulate unavailable download storage where feasible on the emulator; otherwise prove the daemon’s guarded error path through an instrumentation seam. Verify it does not resume as a safe restart. Do not claim M9 reconnection/recheck behavior.
9. **Permission denied:** deny notification permission; verify daemon startup is blocked with a non-sensitive remediation state.
10. **Privacy and teardown:** inspect captured logs and notification state for prohibited sensitive values, then prove the teardown requirements below.

### Required teardown after every automated run

- Daemon/service and Ktor server are stopped.
- Local fixture is shut down.
- Test downloads and recovery records are removed.
- Test credentials are reset/removed according to the test harness.
- Captured logs contain no test credentials, magnet URIs, tracker URLs, or private storage paths.
- Teardown assertions are test outcomes, not best-effort cleanup.

### Evidence and reporting

- Record only actually executed emulator results in `TEST_REPORT.md`, including emulator/API/ABI, fixture identity, scenario outcome, artifacts, and any failure/blocker.
- Do not claim physical-device LAN validation from emulator evidence.
- If fixture, lifecycle, or cleanup behavior fails, record the precise blocker and smallest next experiment. Do not convert a blocker into a pass.

## Exit criteria

- [ ] One foreground daemon owns the native session and Ktor server; `MainActivity` and `TorrentViewModel` no longer own either lifecycle.
- [ ] Backgrounding `MainActivity` does not stop active transfers or emulator-local WebUI availability.
- [ ] Idle queue does not stop the daemon/WebUI; explicit Stop does.
- [ ] Queue mutations are durable, native resume data is atomically checkpointed at most every 30 seconds, and safe Stop meets the five-second guarantee.
- [ ] System-termination recovery and explicit/Force-stop semantics match this specification.
- [ ] Notification permission gate, aggregate privacy-safe notification, and Stop action work as specified.
- [ ] Native/session/recovery failures are recoverable and do not cause silent loss or background retry loops.
- [ ] Fully automated emulator E2E executes the required scenarios using real JNI/libtorrent plus a deterministic local fixture, with verified teardown.
- [ ] `TEST_REPORT.md` contains only actual executed results; M3 makes no physical-device LAN claim.

## Documentation updates required during implementation

- Update `ARCHITECTURE.md` after ownership actually moves.
- Keep `CONTEXT.md` and ADR-0011 consistent if terminology or ownership changes.
- Update `ROADMAP.md` only when evidence supports an exit-criterion status change.
- Update `TEST_REPORT.md` only with real, executed emulator evidence.
- Record any added third-party dependency and license in `THIRD_PARTY_NOTICES.md` before it is introduced.
