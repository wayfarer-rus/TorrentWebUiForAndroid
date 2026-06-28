# Skill: Android Storage (SAF)

Operational guide for Android Storage Access Framework integration.

## Permission Acquisition

- The Android app owns folder permission acquisition.
- Use `Intent.ACTION_OPEN_DOCUMENT_TREE` for folder selection.
- Call `takePersistableUriPermission` to persist URI permissions across reboots.
- Store persisted URI grants in a secure, app-private location.

## Browser Limitations

- The browser WebUI **cannot directly invoke arbitrary picker behavior**.
- Adding new storage destinations requires using the Android app.
- The WebUI presents only the list of already-approved destinations.

## Named Destinations

- Expose only named, approved destinations in the WebUI.
- Examples: "Internal Storage / Downloads", "USB Drive / Media".
- Never expose document URIs (`content://`) or raw paths to users.

## Persistence

- Persist URI permissions where possible.
- On app restart, revalidate persisted permissions.
- Non-persisted permissions require re-authorization.

## Testing Requirements

- Test USB detach and reconnect scenarios.
- Test permission loss and re-authorization flow.
- Test storage full conditions.
- Test multiple simultaneous destinations.
- Record results in [TEST_REPORT.md](../../TEST_REPORT.md).

## Recovery States

| Event | Expected Behavior |
|-------|-------------------|
| USB disconnect | Destination marked unavailable; torrents paused |
| USB reconnect | Destination revalidated; torrents resumed |
| Permission revoked | Destination removed; user re-authorizes via Android app |
| Storage full | Torrents paused; user notified |
