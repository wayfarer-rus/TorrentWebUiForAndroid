# Licensing Review for Public Source Publication

**Reviewed:** 2026-08-19
**Scope:** Repository manifests, lockfiles, native linkage, pinned libtorrent submodule, bundled WebUI assets, and direct build/test dependencies.

This is an engineering review, not legal advice.

## Decision

Original project code is published under **Apache License 2.0**. It is preferred over MIT here because it remains permissive while adding an explicit patent grant and termination terms. Third-party material is excluded from that relicensing and retains its upstream license.

No declared GPL or LGPL dependency was found in the Android or WebUI build. Static linking does not turn the permissively licensed libtorrent/Boost code into copyleft code, but binary notice obligations still apply.

## Material findings addressed

- The pinned libtorrent v2.0.10 code is primarily BSD-3-Clause, not Boost-licensed as a whole. Its complete upstream `LICENSE` also identifies additional per-file terms, including an APSL-2.0 Apple route header. The Android build does not intentionally compile Apple route code.
- JUnit 4.13.2 is EPL-1.0, not EPL-2.0, and is test-only.
- Runtime notices now include previously omitted Ktor, Netty, kotlinx serialization/coroutines, AndroidX, Kotlin, and Svelte components.
- Gradle generates an APK `open_source_licenses/` asset directory containing the project license/NOTICE, inventory, complete pinned libtorrent license, Boost license, Netty NOTICE, and Svelte MIT license.
- The Android Asset Studio robot foreground was replaced with original geometric artwork, avoiding Android robot attribution ambiguity.

## Primary sources

- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- [Pinned libtorrent commit](https://github.com/arvidn/libtorrent/commit/74bc93a37a5e31c78f0aa02037a68fb9ac5deb41)
- [Pinned libtorrent license](https://github.com/arvidn/libtorrent/blob/74bc93a37a5e31c78f0aa02037a68fb9ac5deb41/LICENSE)
- [Apple Public Source License 2.0](https://opensource.apple.com/apsl/)
- [Boost Software License 1.0](https://www.boost.org/LICENSE_1_0.txt)
- [AndroidX](https://github.com/androidx/androidx)
- [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt)
- [Ktor](https://github.com/ktorio/ktor/blob/main/LICENSE)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization/blob/HEAD/LICENSE.txt)
- [Netty license and NOTICE](https://github.com/netty/netty/tree/4.1)
- [Svelte license](https://github.com/sveltejs/svelte/blob/main/LICENSE.md)
- [JUnit 4.13.2 license](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt)
- [Playwright license](https://github.com/microsoft/playwright/blob/main/LICENSE)

## Residual release checks

Before distributing a production APK, inspect the final binary/object map to confirm Apple-specific libtorrent route code is absent, verify the generated license assets in the APK, and repeat the dependency/license inventory. Copyright ownership and jurisdiction-specific questions require maintainer or qualified legal review.
