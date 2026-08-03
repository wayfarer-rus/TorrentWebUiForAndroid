# 09 — Automate onboarding interruption and credential recovery

**What to build:** Extend the real-APK onboarding harness with bounded interruption and credential scenarios proving that setup resumes safely, established installations migrate once, completion remains durable, and users can recover browser access.

**Blocked by:** 03 — Reset the Password from Android; 06 — Choose another Approved Destination during onboarding; 07 — Choose another Password during onboarding; 08 — Automate the clean-install onboarding happy path.

**Status:** done

- [x] Refreshing, closing the browser, or restarting the process at each onboarding step resumes the first unresolved step.
- [x] A real-APK scenario rejects the Recommended Destination, selects another backend-validated canonical folder, and completes onboarding with that Approved Destination.
- [x] One-time migration recognizes established queue, Approved Destination, and non-default Password evidence only when no onboarding marker exists.
- [x] An incomplete marker with an Approved Destination remains incomplete and cannot be migrated past the Password decision.
- [x] **Choose another password** persists safely, reloads the page, and requires the new HTTP Basic credential.
- [x] Closing during password reauthentication retains earlier onboarding progress.
- [x] Android Password Reset restores access with `start123`, invalidates the previous credential, and never requests the current Password.
- [x] Later permission loss, unavailable storage, last-destination removal, and daemon failure use normal recovery states without reopening Consumer Onboarding.
- [x] Every scenario performs complete cleanup and checks logs for credentials, authorization headers, magnets, private trackers, and routine destination paths.
