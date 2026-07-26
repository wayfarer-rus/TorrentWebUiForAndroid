# 05 — Complete the recommended onboarding path

**What to build:** Give a first-time household user one straightforward path from Onboarding Readiness to the normal WebUI: confirm the real canonical Recommended Destination, choose **Set it later** for the Password, and finish setup durably.

**Blocked by:** 04 — Gate first use through Onboarding Readiness.

**Status:** implemented

- [x] The backend derives the proposal from the Android-reported primary Storage Volume, and the WebUI shows the resulting real canonical `<primary storage volume>/Download/Torrents` path without an alias or synthetic label.
- [x] The directory is created only after user confirmation.
- [x] One backend operation creates, canonicalizes, validates, approves, and records the directory as Latest Selected Destination.
- [x] Failure never claims approval, never deletes pre-existing content, and leaves onboarding resumable.
- [x] **Set it later** invokes an authenticated defer operation that is accepted only while onboarding is incomplete, records an explicit durable Password decision, and neither changes nor displays the default Password.
- [x] Completion occurs only while Onboarding Readiness is **Ready**, an Approved Destination exists, and the Password decision is recorded.
- [x] Successful completion unlocks normal torrent controls and survives browser, process, and daemon restarts.
- [x] Later permission, storage, destination, or daemon failures use normal recovery states and never reopen Consumer Onboarding.
- [x] Backend tests cover recursive creation, reuse of an existing directory, canonicalization and filesystem failures, catalog/latest-selection persistence failures, and safe handling of partially created empty directories.
- [x] Integration and browser tests cover the full recommended path, durable completion, and recovery non-regression.
