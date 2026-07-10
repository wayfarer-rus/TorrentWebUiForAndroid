---
Type: research
Status: resolved
Labels: wayfinder:research, assigned-to: claude
---

## Question

How do we handle SSD path changes and USB disconnects gracefully?

The spec needs to address:
- **USB disconnect**: what happens when the SSD is unplugged mid-download? Do we pause all torrents? Queue them for retry?
- **Path change**: if the SSD mount point changes (replug, different USB port), how do we find existing torrents?
- **Storage unavailable at startup**: if the SSD isn't connected when the app starts, do we wait? Error out? Use internal storage as fallback?
- **User notification**: how does the user know the download path is unavailable?

This is a task ticket because it requires investigating Android's Storage Access Framework and USB host API to understand what URIs and path resolution options are actually available. The answer will inform the storage section of the spec.

Blocked on MVP scope (ticket 01) — minimum viable handling differs from full resilience.

## Answer

### Android Storage Access Framework (SAF) fundamentals

**How SAF works:**
1. User grants access via `Intent.ACTION_OPEN_DOCUMENT_TREE` → returns a `Uri` (e.g., `content://com.android.externalstorage.documents/tree/1234-5678`)
2. App calls `takePersistableUriPermission(uri, flags)` to persist access across reboots
3. App can then use `ContentResolver.openDocument(uri, mode, cancellationSignal)` to access files
4. SAF URIs are **persistent** — they don't change when the USB drive is replugged or mount point changes

**Key insight:** SAF URIs are **stable identifiers**, not file paths. The URI `content://com.android.externalstorage.documents/tree/1234-5678` refers to the same storage location regardless of:
- USB port changes
- Mount point changes (`/storage/1234-5678` vs `/mnt/media_rw/1234-5678`)
- Drive reformatting (as long as the volume ID stays the same)

**Limitation:** If the drive is **formatted** or **removed and replaced with a different drive**, the URI becomes invalid. The volume ID changes, so the old URI no longer resolves.

---

### USB disconnect handling (mid-download)

**What happens:**
- When USB drive is unplugged, Android fires `Intent.ACTION_MEDIA_UNMOUNTED` (or `ACTION_MEDIA_EJECT`)
- The SAF URI becomes **unreachable** — `ContentResolver.openDocument()` throws `FileNotFoundException`
- libtorrent continues trying to write to the path → fails silently or corrupts state

**Recommended handling:**

```kotlin
// In TorrentSession (or a StorageMonitor singleton)
private fun monitorStorage() {
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_MEDIA_UNMOUNTED)
        addAction(Intent.ACTION_MEDIA_EJECT)
        addDataScheme("file") // Not needed for SAF, but good practice
    }
    
    context.registerReceiver(storageReceiver, filter)
}

private val storageReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT -> handleStorageUnavailable()
        }
    }
}

private fun handleStorageUnavailable() {
    // 1. Pause all active torrents
    TorrentSession.pauseAllTorrents()
    
    // 2. Post event to UI
    EventBus.post(StorageEvent.Unavailable)
    
    // 3. Schedule retry (e.g., every 10 seconds)
    retryJob = viewModelScope.launch {
        while (true) {
            delay(10_000L) // Check every 10 seconds
            if (isStorageAvailable()) {
                retryJob?.cancel()
                EventBus.post(StorageEvent.Available)
                TorrentSession.resumeAllTorrents()
                return@launch
            }
        }
    }
}

private fun isStorageAvailable(): Boolean {
    val uri = preferences.getString(PREF_STORAGE_URI, null) ?: return false
    return try {
        context.contentResolver.openFileDescriptor(uri, "r")?.close()
        true
    } catch (e: FileNotFoundException) {
        false
    }
}
```

**User notification:**
- **Persistent banner**: "Download storage unavailable. Torrents paused."
- **Action button**: "Retry" (immediate check) or wait for auto-retry (10s interval)
- **Diagnostics panel**: Show storage status ("Available" / "Unavailable")

---

### Path change recovery across reboots

**The good news:** SAF URIs are **persistable**. Once the user grants access, you can persist it across reboots:

```kotlin
// After user selects folder via SAF:
val uri = data.data // Uri from Intent.ACTION_OPEN_DOCUMENT_TREE

// Persist access (survives reboot)
val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
contentResolver.takePersistableUriPermission(uri, flags)

// Save URI for later use
preferences.edit()
    .putString(PREF_STORAGE_URI, uri.toString())
    .apply()
```

**On app startup:**
```kotlin
// In TorrentSession.init():
val uriString = preferences.getString(PREF_STORAGE_URI, null) ?: run {
    // No storage granted yet — show onboarding flow
    EventBus.post(StorageEvent.NotConfigured)
    return false
}

val uri = Uri.parse(uriString)

// Verify URI is still valid
val isValid = try {
    contentResolver.openFileDescriptor(uri, "r")?.close()
    true
} catch (e: FileNotFoundException) {
    false
}

if (!isValid) {
    // URI is stale — drive was formatted or replaced
    preferences.edit().remove(PREF_STORAGE_URI).apply()
    EventBus.post(StorageEvent.Invalid) // Trigger onboarding again
    return false
}

// URI is valid — use it for downloads
val savePath = resolveSavePath(uri) // e.g., "downloads/torrents"
```

**Edge case: URI becomes invalid**
- **Cause**: Drive formatted, replaced with different drive, or USB port changed (volume ID changes)
- **Detection**: `openFileDescriptor()` throws `FileNotFoundException`
- **Recovery**: Show onboarding flow again (`ACTION_OPEN_DOCUMENT_TREE`)

---

### Storage unavailable at startup

**Three options:**

#### Option 1: Wait for storage (RECOMMENDED for MVP)
- **Behavior**: App waits up to 30 seconds for storage to become available
- **UI**: "Waiting for download storage..." banner with countdown
- **Fallback**: If storage doesn't appear in 30s, show error and disable torrent operations
- **Pros**: Simple, matches user expectation (plugged in drive should work)
- **Cons**: Delay on startup if drive is slow to mount

```kotlin
private suspend fun waitForStorage(timeoutMs: Long = 30_000L): Boolean {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < timeoutMs) {
        if (isStorageAvailable()) return true
        delay(1000L) // Check every second
    }
    return false
}
```

#### Option 2: Error out immediately
- **Behavior**: Show "No download storage configured" error, disable all torrent operations
- **UI**: Persistent error banner with "Configure storage" button
- **Pros**: Fast startup, clear error message
- **Cons**: User must manually fix before using app

#### Option 3: Fallback to internal storage
- **Behavior**: Use app-private external storage (`getExternalFilesDir()`) as fallback
- **UI**: Warning banner "Using internal storage. Add USB drive for more space."
- **Pros**: App always works, even without USB drive
- **Cons**: Limited space (app-private), defeats purpose of SSD

**Recommendation: Option 1 (wait) for MVP.**
- Rationale: Users expect the app to work when they plug in a drive. 30s wait is acceptable.
- Document Option 2 as fallback if startup delay becomes a problem.

---

### Implementation plan for spec

**MVP scope:**
1. **Storage onboarding**: `ACTION_OPEN_DOCUMENT_TREE` → persist URI via `takePersistableUriPermission()`
2. **Storage validation**: On startup, verify URI is valid (`openFileDescriptor()`)
3. **USB disconnect handling**: Register `BroadcastReceiver` for `ACTION_MEDIA_UNMOUNTED`, pause all torrents, show banner
4. **Auto-retry**: Every 10 seconds, check if storage is available again; resume torrents when it returns
5. **Startup wait**: Wait up to 30 seconds for storage; show "Waiting..." banner

**Out of scope (full feature set):**
- Multiple storage destinations (named folders)
- Storage health monitoring (SMART data, error rates)
- Automatic fallback to internal storage if USB unavailable
- Storage usage stats (used/total space)

---

### Android permissions needed

```xml
<!-- No special permissions required for SAF -->
<!-- SAF handles permissions via URI grants (takePersistableUriPermission) -->

<!-- Optional: For USB host API (not needed for MVP) -->
<!-- <uses-feature android:name="android.hardware.usb.host" /> -->
```

**Key point:** SAF **does not require** `MANAGE_EXTERNAL_STORAGE` or any dangerous permissions. The URI grant system handles access control securely.

---

### Testing considerations

**Device testing required:**
1. **USB disconnect mid-download**: Unplug drive while torrent is active → verify torrents pause, banner shows
2. **Replug same drive**: Replug after disconnect → verify auto-retry resumes torrents
3. **Format drive**: Format USB drive while app is running → verify URI becomes invalid, onboarding triggers
4. **Different USB port**: Replug same drive to different port → verify URI still works (volume ID unchanged)
5. **Different drive**: Plug in a different USB drive → verify old URI fails, onboarding triggers
6. **Startup without drive**: Start app with no USB drive → verify 30s wait, then error banner
7. **Startup with slow mount**: Start app while drive is mounting → verify wait completes successfully

**Automated tests:**
- Mock `ContentResolver` to simulate `FileNotFoundException`
- Test `isStorageAvailable()` with fake URI
- Verify broadcast receiver pauses torrents on `ACTION_MEDIA_UNMOUNTED`

---

## Comments
- SAF URIs are stable identifiers, not file paths — they survive mount point changes but break if the drive is formatted or replaced.
- `takePersistableUriPermission()` is the key API — it persists URI access across reboots without requiring dangerous permissions.
- USB disconnect handling requires a `BroadcastReceiver` for `ACTION_MEDIA_UNMOUNTED` + auto-retry loop.
- MVP should wait up to 30 seconds for storage at startup, then error out if unavailable.
- Full feature set can add multiple destinations, health monitoring, and automatic fallback.