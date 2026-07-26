# 06 — Choose another Approved Destination during onboarding

**What to build:** Let a first-time user reject the Recommended Destination and select another real device directory through the existing WebUI Directory Browser, while preserving the same backend validation and completion rules.

**Blocked by:** 05 — Complete the recommended onboarding path.

**Status:** implemented

- [x] **Choose another folder** opens the existing backend-validated WebUI Directory Browser within Consumer Onboarding.
- [x] Only mounted Storage Volumes and readable child directories returned by the backend are navigable.
- [x] Pasted paths are accepted only after canonicalization, volume confinement, directory, and writability validation.
- [x] SAF document URIs, inferred paths, aliases, labels, and synthetic paths are rejected.
- [x] Confirming a valid path records one Approved Destination and updates Latest Selected Destination.
- [x] Invalid or unavailable paths leave onboarding incomplete with a consumer-readable error.
- [x] Refreshing or interrupting selection retains only durable backend progress, never an unconfirmed browser-only destination.
- [x] Browser and backend tests cover primary, removable, pasted, rejected, disconnected, and canonical-path cases.
