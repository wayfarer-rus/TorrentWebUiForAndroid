# Milestone 6 — Consumer Onboarding Specification

## Problem Statement

A first-time household user currently reaches a technical Android fallback screen and a full torrent-control WebUI without a guided path that confirms the service is ready, establishes a usable download location, and offers an understandable password choice. Storage setup requires filesystem knowledge, WebUI access settings are fixed, and password recovery is unavailable when the current password is unknown.

The product must remain browser-first. Android must stay a bare-bones platform bootstrap, health, and recovery surface rather than becoming a second application UI. Consumer Onboarding must therefore live in the authenticated WebUI while respecting Android-only permission and service-lifecycle boundaries.

## Solution

Add a mandatory, resumable, WebUI-owned Consumer Onboarding journey for installations that have not completed setup. It presents consumer-level Onboarding Readiness, offers a one-tap Recommended Destination at the verified canonical `<primary storage volume>/Download/Torrents` path, allows selection of another Approved Destination, and ends with an optional password choice.

Normal torrent controls remain unavailable until Onboarding Readiness is **Ready**, at least one Approved Destination exists, and the user either changes the Password or explicitly chooses **Set it later**. Completion is durable. Later permission revocation, unavailable storage, destination removal, or daemon failure uses normal recovery states and never makes an established installation appear new again.

Android remains visually close to its current fallback screen. It displays and configures the WebUI Port, can reset the Password to `start123` without knowing the current Password, and retains platform-permission, daemon-health, and emergency Start/Stop responsibilities. It does not contain Consumer Onboarding, torrent controls, storage selection, or password-entry fields.

## User Stories

1. As a first-time household user, I want a guided WebUI setup journey, so that I can make the product usable without technical knowledge.
2. As a first-time household user, I want Android setup to remain minimal, so that I am not forced through two competing setup experiences.
3. As a first-time household user, I want the WebUI to tell me whether setup can proceed, so that I do not need to understand daemon internals.
4. As a first-time household user, I want the WebUI to advance automatically when the service becomes ready, so that I do not repeatedly retry it manually.
5. As a user whose Android permissions are incomplete, I want the WebUI to tell me that action is needed on Android, so that I know where to resolve the problem.
6. As a user whose service cannot start, I want a concise service-unavailable state, so that technical diagnostics do not overwhelm me.
7. As a first-time household user, I want a recommended download folder, so that I do not need to navigate Android storage.
8. As a first-time household user, I want to see the real canonical recommended path, so that I know exactly where downloads will be stored.
9. As a first-time household user, I want the recommended directory created only after I confirm it, so that setup does not silently modify storage.
10. As a first-time household user, I want the recommended directory validated before it is approved, so that setup cannot finish with an unusable destination.
11. As a user with a preferred storage layout, I want to choose another folder, so that I am not forced to use the recommendation.
12. As a user choosing another folder, I want the existing backend-validated directory browser, so that only safe canonical paths can be approved.
13. As a user with removable storage, I want only currently mounted and backend-validated volumes presented, so that setup does not invent unavailable destinations.
14. As a first-time household user, I want normal torrent controls hidden until required setup is complete, so that I cannot enter an unusable state accidentally.
15. As a user interrupted during setup, I want to resume at the unresolved step, so that completed work is not repeated.
16. As a user who refreshes the browser during setup, I want server-owned progress restored, so that progress does not depend on browser-local state.
17. As a user satisfied with the existing Password, I want to choose **Set it later**, so that password change is not an artificial setup barrier.
18. As a user who wants another Password, I want to enter and confirm only the new value, so that the default Password is never displayed or emphasized.
19. As a user changing the Password during onboarding, I want a clear browser reauthentication handoff, so that the immediate HTTP Basic credential change is understandable.
20. As a user whose browser closes during password reauthentication, I want prior readiness and destination progress retained, so that setup does not restart.
21. As an established user, I want onboarding completion to remain durable, so that ordinary recoverable failures never return me to a first-run wizard.
22. As an established user whose storage disconnects, I want the normal storage-recovery experience, so that interruption is not confused with first-time setup.
23. As an established user whose Android permission is revoked, I want the normal permission-recovery experience, so that previously completed onboarding remains complete.
24. As an established user whose daemon fails, I want normal health and retry controls, so that the product does not erase my established state.
25. As the Android device owner, I want to see the configured WebUI Port, so that I know which port to use on the LAN.
26. As the Android device owner, I want to change the WebUI Port locally, so that I can resolve a deployment conflict without entering the WebUI.
27. As the Android device owner, I want an invalid or unavailable port rejected without disrupting the current WebUI, so that a configuration mistake does not remove access.
28. As a user with active transfers, I want a port change to restart only the WebUI server, so that torrent transfers continue uninterrupted.
29. As a browser user connected to the old port, I want the change behavior to be explicit, so that I know to reconnect on the new port.
30. As the Android device owner who forgot the Password, I want to reset it locally without knowing the current value, so that WebUI access is recoverable.
31. As the Android device owner, I want Password Reset to require confirmation, so that an accidental tap does not invalidate browser credentials.
32. As a security-conscious household user, I want WebUI pages, APIs, and real-time endpoints authenticated, so that direct LAN access cannot bypass the Password.
33. As a privacy-conscious user, I want onboarding and diagnostics to avoid logging credentials, torrent metadata, tracker data, and destination paths, so that setup does not leak private state.
34. As a new user, I want setup to finish in under three minutes under normal conditions, so that the appliance feels consumer-ready.
35. As a maintainer, I want onboarding, Android recovery controls, and normal torrent operation to consume one backend model, so that the two surfaces cannot drift.
36. As a maintainer, I want M6 delivered on a clean M4 baseline, so that storage behavior and test evidence remain independently reviewable.

## Implementation Decisions

1. **Surface ownership:** Consumer Onboarding belongs exclusively to the authenticated WebUI. Android owns Android Startup Bootstrap, daemon health, emergency Start/Stop, WebUI Port configuration, and local Password Reset.
2. **No duplicate application UI:** Android will not gain a queue, magnet entry, torrent controls, destination selection, onboarding wizard, or general settings area.
3. **Durable backend authority:** Onboarding progress and completion are application-owned durable state. Browser-local storage is not authoritative.
4. **Completion contract:** Completion requires current Onboarding Readiness to be **Ready**, at least one Approved Destination, and a recorded password decision of changed or deferred.
5. **Resume contract:** Before completion, the backend reports enough state for the WebUI to resume at the first unresolved step after refresh, browser closure, or process restart.
6. **Durable completion:** Once completion is recorded, later permission, storage, destination, or daemon failures cannot clear it. Clearing application data or an explicit application reset may clear it.
7. **Onboarding Readiness:** While Ktor is reachable, the WebUI receives only **Ready**, **Action needed on Android**, or **Service unavailable**. **Action needed on Android** represents a reachable permission-blocked server after runtime permission loss; **Service unavailable** represents an unavailable backend/native session while Ktor remains alive. Pre-bootstrap permission work, Ktor bind failure, and a fully stopped server are reported only by Android because no WebUI can render them. Native-loaded flags, engine versions, permission names, stack traces, and internal lifecycle details are excluded.
8. **Readiness polling:** While reachable setup is blocked on readiness, the WebUI polls a typed authenticated backend status and advances automatically when ready. Polling is bounded per request and uses a modest interval rather than a busy loop; it does not pretend to diagnose an unreachable WebUI server.
9. **Primary integration contract:** Authenticated `GET /api/onboarding/status` returns readiness, durable completion, destination presence, and password-decision state from the shared backend model.
10. **Recommended Destination:** The backend derives the proposal from the Android-reported primary Storage Volume and the `Download/Torrents` suffix. The authenticated response always returns the resulting real canonical path, never an alias or synthetic label.
11. **Confirmed creation:** Selecting the recommendation invokes one backend operation that creates missing directories, canonicalizes the result, validates volume confinement and writability, records the Approved Destination, and updates Latest Selected Destination.
12. **Failure safety:** If creation, canonicalization, validation, catalog persistence, or latest-selection persistence fails, the operation reports failure and does not claim approval. It never deletes pre-existing content. A newly created empty directory may be retained when rollback cannot be proven safe.
13. **Alternate destination:** **Choose another folder** reuses the existing WebUI Directory Browser and backend validation rules. Generic SAF document URIs, inferred paths, labels, and aliases remain forbidden.
14. **Onboarding enforcement:** Before completion, authenticated page load renders onboarding instead of normal torrent controls, and authenticated torrent-mutation APIs reject direct calls with `409 onboarding_incomplete`. Only the status, readiness, storage/destination operations required by onboarding, onboarding password operations, and Android-owned recovery controls remain usable; authentication alone cannot bypass the mandatory gate.
15. **Password presentation:** The accepted default remains `start123`. Consumer Onboarding never displays it, repeats it, warns about it, or asks the user to re-enter it.
16. **Password decision:** The final step offers only **Choose another password** and **Set it later**. Authenticated `POST /api/onboarding/password/defer` records deferral without changing the Password and is accepted only while onboarding is incomplete.
17. **Onboarding password change:** Because the user is already authenticated, onboarding asks for a new Password and confirmation only. Authenticated `POST /api/onboarding/password` accepts `{newPassword}` only while onboarding is incomplete; confirmation remains client-side. Normal post-onboarding `POST /api/settings/password` retains `{currentPassword, newPassword}`.
18. **Password validation:** The existing minimum length of four characters and passphrase support remain. Confirmation mismatch is rejected client-side, and the backend independently validates the accepted value.
19. **Change ordering:** A password change is persisted before the changed decision is recorded. Failure leaves onboarding incomplete rather than falsely complete.
20. **HTTP Basic handoff:** After a successful onboarding password change, the WebUI performs a full-page reload so the browser requests the new HTTP Basic credential. Earlier onboarding progress remains durable.
21. **Local Password Reset:** Android provides a confirmed action that resets the Password directly to `start123`. It never asks for the current Password and never accepts a replacement value.
22. **Reset authority:** Physical access to the Android app is sufficient authority for Password Reset. The reset takes effect on the next authentication check and invalidates old browser credentials.
23. **WebUI Port storage:** Android persists one configured WebUI Port. The default is `8080`; accepted values are `1024–65535`.
24. **Port ownership:** The configured and effective port are displayed in Android only. The WebUI cannot change its own listening port, and Android does not need to discover or display the LAN address.
25. **Atomic port switch:** Applying a new port starts a candidate WebUI server on the new port while the existing server remains available. Only after successful binding and durable persistence does ownership switch and the old server stop.
26. **Port-change rollback:** Invalid input, bind failure, or persistence failure stops the candidate and preserves the previous running server, configured port, and active torrent session. Android shows a concise error.
27. **Transfer isolation:** Port switching never initializes, destroys, pauses, or restarts the native torrent session. Existing browser and WebSocket connections on the retired server may disconnect and must reconnect to the new port.
28. **Cold-start bind failure:** If the persisted port cannot bind when no old server exists, the daemon reports WebUI service unavailability through Android rather than silently replacing the user’s configured port. The user can apply another valid port locally.
29. **Shared server controller:** WebUI lifecycle becomes a narrow Android-side controller capable of candidate bind, promotion, and rollback while continuing to use the daemon-owned backend model.
30. **Authentication boundary:** Static WebUI content, onboarding APIs, normal APIs, and WebSocket handshakes require HTTP Basic Authentication. Any outdated page-level-only WebSocket assumption must be removed from documentation and tests.
31. **No sensitive logging:** Credentials, authorization headers, magnet URIs, private tracker URLs, and destination paths are not logged. Android port errors may include the non-sensitive port number but not private application state.
32. **Safe defaults:** No advanced torrent, tracker, bandwidth, VPN, or storage-engine settings appear in onboarding. Existing consumer defaults remain in force automatically.
33. **No new dependency by default:** The work should use existing Kotlin, Compose, Ktor, Svelte, and test tooling. Any unavoidable dependency requires license/source documentation before addition.
34. **M4 baseline precondition:** Implementation begins only after the current M4 work is committed and the working tree is clean. M6 must preserve M4 canonical-path, recovery, and storage-safety behavior.
35. **Established-installation migration:** On first M6 startup without an onboarding marker, a non-empty durable queue, a non-empty Approved Destination catalog, or a non-default Password proves that the installation is established. Such an installation is initialized as completed with the password decision treated as deferred; an installation with none of that evidence enters Consumer Onboarding. This one-time migration exception prevents an upgrade from hiding an existing queue.

## Testing Decisions

1. Tests assert observable behavior and durable state transitions, not private method calls, Compose internals, or Svelte implementation details.
2. The primary automated integration seam is the authenticated Ktor application boundary using real onboarding/auth/catalog persistence with controllable daemon-readiness and storage-volume collaborators. This covers most state, API, authentication, and failure behavior without multiplying seams.
3. Narrow policy tests cover the onboarding completion transition, password-decision ordering, valid port range, candidate promotion, persistence failure, bind rollback, and cold-start failure.
4. Existing authentication-test patterns are extended for onboarding status, password deferral, onboarding password change, Password Reset effects, static content, API routes, and WebSocket handshake protection.
5. Existing destination-management patterns are extended for Recommended Destination derivation, recursive creation, canonicalization, confinement, approval, Latest Selected Destination update, existing-directory reuse, and partial-failure safety.
6. Android UI tests observe that the configured port is shown, valid changes succeed, invalid/unavailable changes preserve the old port, Password Reset requires confirmation, and no password-entry or torrent-management controls appear.
7. WebUI browser tests exercise readiness polling, automatic advancement, recommended and alternate destination paths, refresh/resume behavior, password deferral, password change, reauthentication handoff, and blocking of normal controls before completion.
8. Browser tests explicitly assert that `start123` is absent from rendered onboarding content and recurring post-onboarding warnings.
9. Durable-completion tests simulate later permission revocation, unavailable/removable storage, last-destination removal, and daemon failure and verify that onboarding does not return.
10. Emulator E2E starts from cleared application data and uses the real APK, Android permission flow, JNI/libtorrent session, Ktor server, packaged Svelte assets, authenticated browser, and shared-storage filesystem.
11. Required emulator scenarios include successful default-port setup, interruption and resume at every onboarding step, Recommended Destination creation, alternate destination selection, password deferral, password change and reauthentication, local Password Reset, successful port switch, failed port switch rollback, and continued torrent-session ownership across server replacement.
12. The under-three-minute criterion is measured from the first actionable Android bootstrap screen to the authenticated normal WebUI under normal emulator conditions, excluding tool installation and fixture provisioning.
13. Emulator teardown stops the daemon, closes candidate/old servers, removes test downloads and recovery records, restores the default port and Password, clears onboarding state, shuts down fixtures, and verifies no sensitive values entered logs.
14. Existing M4 storage and lifecycle regression suites run after M6 changes. Previous results are not reused as evidence for the modified tree.
15. Physical-device validation from a separate LAN browser is required before claiming real Android/LAN network behavior complete. Emulator evidence must be labeled emulator-only.
16. `TEST_REPORT.md` records only commands actually run, environment details, observed results, teardown evidence, and explicit residual gaps.
17. Authenticated API tests call torrent mutation routes directly before completion and require `409 onboarding_incomplete`, proving the page-level gate cannot be bypassed.
18. Migration tests cover established queues, existing Approved Destinations, non-default Passwords, and genuinely unused installations without a marker.

### Acceptance Criteria

- [ ] Android remains a bare-bones bootstrap, health, recovery, and emergency-control surface.
- [ ] Consumer Onboarding is rendered only in the authenticated WebUI.
- [ ] Normal torrent controls and direct authenticated torrent-mutation APIs remain unavailable until readiness, destination, and password-decision requirements are satisfied.
- [ ] Setup resumes at the unresolved step after refresh, browser closure, or process restart.
- [ ] Established pre-M6 installations are migrated to completed onboarding without hiding an existing queue.
- [ ] Consumer-facing readiness exposes no technical diagnostics.
- [ ] The one-tap Recommended Destination creates, validates, and approves the real canonical primary-volume `Download/Torrents` directory.
- [ ] Alternate selection uses only backend-validated canonical paths beneath reported Storage Volumes.
- [ ] Password setup offers **Choose another password** or **Set it later** and never renders the default Password.
- [ ] Onboarding password change omits the current-password field and reloads for browser reauthentication.
- [ ] Completion remains durable through later permission, storage, destination, and daemon failures.
- [ ] Android displays the effective WebUI Port and accepts only `1024–65535`.
- [ ] Successful port changes preserve the torrent session; failed changes preserve the previous running server and port.
- [ ] Android Password Reset restores `start123` without the current Password or a replacement field.
- [ ] WebUI content, APIs, and WebSockets reject unauthenticated access.
- [ ] A normal first-time setup completes in under three minutes.
- [ ] Required automated and physical-device evidence is recorded without leaking sensitive data.

## Out of Scope

- Queue, magnet, torrent, destination-browser, or per-torrent controls in Android.
- Android-owned Consumer Onboarding or a duplicate native setup wizard.
- Displaying, discovering, or naming the device’s LAN address or hostname.
- Changing the WebUI Port from the WebUI.
- Entering a replacement Password from Android; Android supports reset to `start123` only.
- Replacing HTTP Basic Authentication, adding user accounts, pairing codes, sessions, OAuth, or cloud identity.
- HTTPS/TLS changes, public Internet exposure, router port forwarding, or automatic network discovery.
- Inspecting or controlling another VPN app or changing VPN split-tunneling behavior.
- Advanced torrent, tracker, bandwidth, queue, or native-engine configuration.
- Generic SAF document destinations, aliases, labels, or synthetic filesystem paths.
- Automatic recovery changes for revoked permissions, disconnected storage, interrupted moves, or corrupted torrent state beyond preserving the established non-onboarding recovery flows.
- Broad WebUI visual redesign; Milestone 7 remains responsible for general polish.
- New third-party dependencies unless separately justified and licensed.
- Implementing M6 on top of the current uncommitted M4 working tree.

## Further Notes

### Delivery Order

1. Commit and establish a clean M4 baseline.
2. Add backend onboarding state, typed status/contracts, and Recommended Destination operation with unit/integration coverage.
3. Add the WebUI server controller, persistent WebUI Port, atomic switch/rollback, and Android Password Reset with tests.
4. Add the WebUI Consumer Onboarding journey and browser-level coverage.
5. Package assets and run focused JVM/Web checks, then M4 regressions and emulator E2E.
6. Perform separate-device LAN validation when hardware is available.
7. Update architecture, roadmap, decisions, third-party notices if needed, and test evidence.
8. Run the repository-required code-review loop before committing implementation.

### Operational Limits and Cleanup

- Work is split into independently reviewable batches because repository requests have a 300-second execution limit.
- Network binds and readiness requests use finite timeouts and recover to an explicit state rather than hanging indefinitely.
- A failed storage operation never approves an invalid path or deletes pre-existing content.
- A failed port operation never abandons a working old server.
- Test cleanup is part of acceptance, not an optional afterthought.

### Documentation

The canonical vocabulary is defined in `CONTEXT.md`. ADR 0026 records that the WebUI owns Consumer Onboarding while Android owns bootstrap and local recovery capabilities. Implementation must reconcile older documentation that uses “Android onboarding” ambiguously and must update roadmap/test status only when supported by actual evidence.

### Residual Risks

- Browser handling of changed HTTP Basic credentials varies; emulator and physical-browser validation must cover reload and re-prompt behavior.
- A port can become unavailable after candidate binding but before later process restarts; cold-start failure remains visible and locally recoverable rather than silently changing configuration.
- Filesystem creation and canonicalization behavior varies across primary and removable Android volumes.
- Real LAN accessibility and browser behavior cannot be claimed from ADB forwarding or emulator-only evidence.
- Existing uncommitted M4 work overlaps server and E2E surfaces and must be committed before implementation begins.
