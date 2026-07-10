---
Type: research
Status: resolved
Labels: wayfinder:research, assigned-to: claude
---

## Question

What third-party dependencies do we need beyond libtorrent, Ktor, and SvelteKit?

We need to identify all libraries required for the spec:
- **Ktor plugins**: what Ktor features do we need? (static file serving, WebSockets, JSON serialization, content negotiation)
- **JSON library**: Kotlinx Serialization? Moshi? Ktor's built-in?
- **SvelteKit dependencies**: what npm packages does the frontend need? (state management, HTTP client, WebSocket client — or are these built-in?)
- **Android dependencies**: do we need WorkManager, NotificationCompat, or is the AndroidX core sufficient?
- **Testing**: what testing frameworks should be included in the spec?

This will inform the build configuration section of the spec.

## Answer

### Current dependencies (already configured in `gradle/libs.versions.toml`)

**Android SDK:**
- `androidx.core:core-ktx` (1.15.0) — Kotlin extensions for Android core APIs
- `androidx.appcompat:appcompat` (1.7.1) — backward compatibility
- `com.google.android.material:material` (1.14.0) — Material Design components

**Jetpack Compose:**
- `androidx.compose:compose-bom` (2025.03.01) — Bill of Materials for version alignment
- `androidx.compose.ui:ui` — Core Compose UI
- `androidx.compose.ui:ui-graphics` — Graphics primitives
- `androidx.compose.ui:ui-tooling-preview` — Preview tooling
- `androidx.compose.material3:material3` — Material 3 components
- `androidx.activity:activity-compose` (1.10.1) — Compose integration with Activity

**ViewModel + Lifecycle:**
- `androidx.lifecycle:lifecycle-viewmodel-ktx` (2.8.7) — ViewModel with Kotlin extensions
- `androidx.lifecycle:lifecycle-runtime-ktx` (2.8.7) — Lifecycle-aware components

**Native (NDK/CMake):**
- `libtorrent-rasterbar` 2.0.10 — C++ torrent library (bundled in `dep/`)
- `Boost` (headers-only) — Bundled in `dep/`, required by libtorrent
- JNI bridge (`torrent_jni.cpp`) — Kotlin ↔ C++ interop

**Testing (current):**
- `junit:junit` (4.13.2) — Unit testing framework
- `androidx.test.ext:junit` (1.3.0) — AndroidX JUnit extensions
- `androidx.test.espresso:espresso-core` (3.7.0) — UI testing framework

---

### Dependencies needed for spec implementation

#### 1. Ktor Server (Milestone 2: Browser Control Proof)

**Core Ktor artifacts:**
```toml
ktor-server-core = { group = "io.ktor", name = "ktor-server-core", version = "3.0.1" }
ktor-server-netty = { group = "io.ktor", name = "ktor-server-netty", version = "3.0.1" }
ktor-serialization-kotlinx-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version = "3.0.1" }
ktor-server-content-negotiation = { group = "io.ktor", name = "ktor-server-content-negotiation", version = "3.0.1" }
ktor-server-static-content = { group = "io.ktor", name = "ktor-server-static-content", version = "3.0.1" }
ktor-server-websockets = { group = "io.ktor", name = "ktor-server-websockets", version = "3.0.1" }
```

**Why Netty over OkHttp?**
- Netty is Ktor's default server engine — well-tested, high performance
- OkHttp is better for client-side; Netty excels at server workloads
- For MVP: Netty is sufficient. OkHttp can be swapped later if needed.

**Ktor plugins required:**
1. **Content Negotiation** — JSON serialization for REST API responses
2. **Static Content** — Serve SvelteKit bundle from Android `assets/` directory
3. **WebSockets** — Real-time torrent status updates to browser
4. **Routing** — Define `/api/*` (REST) and `/ws/*` (WebSocket) routes

#### 2. JSON Library: Kotlinx Serialization

**Recommended:** `org.jetbrains.kotlinx:kotlinx-serialization-json` (1.7.3)

**Why Kotlinx Serialization over Moshi?**
- **Type-safe**: Compile-time code generation, no reflection overhead
- **Ktor-native**: Official Ktor plugin (`ktor-serialization-kotlinx-json`)
- **Modern**: Preferred for new Kotlin projects (Moshi is legacy)
- **Performance**: Faster than Moshi due to code gen

**Usage in spec:**
```kotlin
@Serializable
data class TorrentResponse(
    val id: Long,
    val name: String,
    val state: String,
    val progress: Float,
    val downloadRate: Long,
    val uploadRate: Long,
    val peers: Int
)

// In Ktor route:
get("/api/torrents") {
    val torrents = TorrentSession.getAllTorrentStatus()
    call.respond(torrents) // Automatically serialized to JSON
}
```

#### 3. SvelteKit Frontend (Milestone 2+)

**npm packages (to be installed in `web/` directory):**

```json
{
  "devDependencies": {
    "@sveltejs/adapter-static": "^3.0.0",
    "svelte": "^5.0.0",
    "@sveltejs/kit": "^2.0.0",
    "vite": "^6.0.0"
  }
}
```

**Built-in features (no extra packages needed):**
- **State management**: Svelte stores (`writable`, `readable`, `derived`) — built into Svelte
- **HTTP client**: Native `fetch()` API — no Axios/Fetch wrapper needed
- **WebSocket client**: Native `new WebSocket()` — or lightweight Svelte wrapper if desired
- **Routing**: SvelteKit file-based routing — built-in, no React Router equivalent needed
- **Build tool**: Vite (bundled with SvelteKit) — handles bundling, dev server, production build

**Why no extra state management?**
Svelte's reactivity model (`$store`) is simpler than Redux/Zustand for this use case:
- Torrent list updates are frequent but small
- No complex async flows or side effects (Ktor WebSocket handles real-time updates)
- Svelte stores are sufficient for MVP

**Static export configuration:**
```javascript
// svelte.config.js
import adapter from '@sveltejs/adapter-static';

export default {
  kit: {
    adapter: adapter({
      pages: 'build',
      assets: 'build',
      fallback: undefined // No SPA fallback needed — Ktor serves index.html
    })
  }
};
```

**How it integrates with Android:**
1. Build SvelteKit app → produces `web/build/` directory (HTML/CSS/JS)
2. Copy `web/build/*` to Android `app/src/main/assets/www/`
3. Ktor serves from `assets/www/` via static content plugin

#### 4. Android-specific dependencies (Milestones 3-6)

**Foreground Service + Notification (Milestone 3):**
```toml
# Already in AndroidX core — no extra dependency needed
# ForegroundServiceType: dataSync (for torrent engine)
# NotificationCompat.Builder for status notification
```

**Storage Access Framework (Milestone 4):**
```toml
# SAF is part of Android platform — no extra dependency
# Use Intent.ACTION_OPEN_DOCUMENT_TREE to pick folder
# Store URI in SharedPreferences
```

**VPN Integration (Milestone 5):**
```toml
# ExpressVPN uses Android's VpnService API (platform-level)
# No extra dependency — user configures ExpressVPN app-specific routing
```

**Optional: WorkManager (future consideration)**
- For reboot recovery (post-MVP): `androidx.work:work-runtime-ktx` (2.9.0)
- Not needed for MVP — document as future enhancement

#### 5. Testing dependencies (spec-wide)

**Unit testing (Kotlin/JVM):**
```toml
# Current: junit:junit 4.13.2
# Add for modern testing:
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version = "1.9.0" }
turbine = { group = "app.cash.turbine", name = "turbine", version = "1.2.0" }
mockk = { group = "io.mockk", name = "mockk", version = "1.13.12" }
```

**Ktor server testing:**
```toml
ktor-server-test-host = { group = "io.ktor", name = "ktor-server-test-host", version = "3.0.1" }
```

**SvelteKit testing:**
```json
{
  "devDependencies": {
    "@sveltejs/kit": "^2.0.0", // Includes Vitest integration
    "vitest": "^2.0.0" // Test runner (bundled with SvelteKit)
  }
}
```

**Instrumented testing (Android):**
```toml
# Current: androidx.test.espresso:espresso-core 3.7.0
# Add for Compose UI testing:
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
```

---

### Summary: Complete dependency list for spec

| Category | Dependency | Version | Purpose |
|----------|-----------|---------|---------|
| **Ktor** | `ktor-server-core` | 3.0.1 | HTTP server engine |
| Ktor | `ktor-server-netty` | 3.0.1 | Netty server implementation |
| Ktor | `ktor-serialization-kotlinx-json` | 3.0.1 | JSON content negotiation |
| Ktor | `ktor-server-content-negotiation` | 3.0.1 | Content negotiation plugin |
| Ktor | `ktor-server-static-content` | 3.0.1 | Serve SvelteKit bundle |
| Ktor | `ktor-server-websockets` | 3.0.1 | WebSocket support |
| **JSON** | `kotlinx-serialization-json` | 1.7.3 | Type-safe JSON serialization |
| **Android** | (already configured) | — | Compose, ViewModel, Lifecycle |
| **Native** | libtorrent 2.0.10 (bundled) | — | Torrent engine |
| **Testing** | `kotlinx-coroutines-test` | 1.9.0 | Coroutine testing utilities |
| Testing | `turbine` | 1.2.0 | Flow testing (assert emissions) |
| Testing | `mockk` | 1.13.12 | Mocking framework |
| Testing | `ktor-server-test-host` | 3.0.1 | Ktor integration testing |
| Testing | `vitest` (npm) | 2.0.0 | SvelteKit test runner |

---

### Build configuration updates needed

**1. `gradle/libs.versions.toml` — add Ktor and testing deps:**
```toml
[versions]
ktor = "3.0.1"
kotlinxSerialization = "1.7.3"
coroutinesTest = "1.9.0"
turbine = "1.2.0"
mockk = "1.13.12"

[libraries]
ktor-server-core = { group = "io.ktor", name = "ktor-server-core", version.ref = "ktor" }
ktor-server-netty = { group = "io.ktor", name = "ktor-server-netty", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-server-content-negotiation = { group = "io.ktor", name = "ktor-server-content-negotiation", version.ref = "ktor" }
ktor-server-static-content = { group = "io.ktor", name = "ktor-server-static-content", version.ref = "ktor" }
ktor-server-websockets = { group = "io.ktor", name = "ktor-server-websockets", version.ref = "ktor" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutinesTest" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
ktor-server-test-host = { group = "io.ktor", name = "ktor-server-test-host", version.ref = "ktor" }

[plugins]
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version = "2.1.20" }
```

**2. `app/build.gradle.kts` — apply plugins and add dependencies:**
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization) // NEW: enable Kotlinx Serialization plugin
}

dependencies {
    // ... existing deps ...

    // Ktor Server (Milestone 2)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.static.content)
    implementation(libs.ktor.server.websockets)

    // JSON serialization
    implementation(libs.kotlinx.serialization.json)

    // Testing (unit + integration)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
}
```

**3. `web/package.json` — SvelteKit dependencies (to be created):**
```json
{
  "devDependencies": {
    "@sveltejs/adapter-static": "^3.0.0",
    "svelte": "^5.0.0",
    "@sveltejs/kit": "^2.0.0",
    "vite": "^6.0.0"
  }
}
```

---

## Comments
- This audit identifies all dependencies needed for the spec, from Ktor server setup (Milestone 2) through testing infrastructure.
- Kotlinx Serialization is preferred over Moshi for new projects — type-safe, Ktor-native, better performance.
- SvelteKit requires minimal npm packages — state management (Svelte stores), HTTP (fetch API), and WebSocket (native) are all built-in.
- Android-specific features (foreground service, SAF, VPN) use platform APIs — no extra dependencies needed.
- Testing infrastructure includes JUnit, Turbine (Flow testing), MockK (mocking), and Ktor test host for integration tests.