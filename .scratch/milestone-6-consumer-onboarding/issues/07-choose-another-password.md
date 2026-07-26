# 07 — Choose another Password during onboarding

**What to build:** Let an already authenticated first-time user choose another Password as the final onboarding step without re-entering or seeing the default, then hand control back to the browser's HTTP Basic Authentication flow safely.

**Blocked by:** 05 — Complete the recommended onboarding path.

**Status:** implemented

- [x] The final step offers only **Choose another password** and **Set it later** and never renders or emphasizes `start123`.
- [x] Choosing another asks only for a new Password and confirmation.
- [x] Confirmation mismatch is rejected in the browser, and the backend independently enforces the existing minimum length.
- [x] The authenticated onboarding-password operation accepts only the new value and only while onboarding is incomplete.
- [x] Password persistence succeeds before the changed decision is recorded; failure leaves onboarding incomplete.
- [x] Success performs a full-page reload so the browser requests the new HTTP Basic credential.
- [x] Closing the browser during reauthentication does not lose readiness, destination, or Password-decision progress.
- [x] The normal post-onboarding password endpoint continues requiring current and new values.
- [x] API and browser tests cover defer, mismatch, invalid values, persistence failure, successful reauthentication, stale credentials, and interrupted reload.
