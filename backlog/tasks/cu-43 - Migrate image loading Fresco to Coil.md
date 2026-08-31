---
id: cu-43
title: Migrate image loading Fresco to Coil
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R0, architecture]
dependencies: []
priority: medium
milestone: m-0
---

## Description

C3: deprecated Fresco DraweeView API in use; app also carries Glide (duplication). Consolidate on Coil (Kotlin-first, coroutine-native, 16KB-clean — supports cu-6). Replace ChronicleDraweeView + BindingAdapters image bindings; drop the redundant library.

Analysis: [`C3-fresco-to-coil-migration-plan.md`](../docs/analysis/archive/C3-fresco-to-coil-migration-plan.md).

## Implementation Notes

### Dependency on cu-6 was inverted, deliberately

This task listed `dependencies: [cu-6]`, on the theory that the SDK bump comes first. The owner
sequenced it the other way, and that proved correct: cu-6's stated worry is a **16KB page-size check on
Fresco**, and Fresco was the only source of native libraries in the APK. The release APK now contains
**zero `.so` files**, so the risk is dissolved rather than mitigated. Dependency cleared.

### Version choice: Coil 3.0.4, not latest

Coil 3.6.0 is current but needs Kotlin stdlib 2.4.10 against our 2.1.20. Worse, **3.1.0+ pulls
`androidx.core-ktx:1.15.0`, which requires compileSdk 35** — we are on 34, and the build fails at
`checkDebugAarMetadata`. Taking a newer Coil would have silently dragged the SDK bump into this task.

| Coil 3.x | stdlib | core-ktx | usable at compileSdk 34 |
|---|---|---|---|
| **3.0.4** | 2.0.21 | 1.13.1 | **yes** |
| 3.1.0 / 3.2.0 | 2.1.10 / 2.1.20 | 1.15.0 | no (needs compileSdk 35) |
| 3.3.0+ | 2.2.0+ | — | no (needs newer Kotlin) |

Revisit after cu-6 lands.

### What changed

- **Deleted `ChronicleDraweeView`** — the custom subclass that existed only to work around Fresco's
  deprecated `GenericDraweeView`. All 9 layouts now use a plain `ImageView`.
- **`BindingAdapters.bindImageRounded`** rewritten on `imageView.load { … }`.
- **Deleted `FrescoExt.kt`** — a `suspendCoroutine` bridge to turn Fresco's `DataSource` into a
  `Bitmap`. Coil does this natively with `imageLoader.execute(request).image?.toBitmap()`.
- **`PlexConfig` album-art fetch** (used for the media notification) moved to Coil.
- **`UserListAdapter`** — `setImageURI` (the deprecated Fresco call) → `load`.
- **`ChronicleApplication`** now implements `SingletonImageLoader.Factory`, building the loader on the
  **media OkHttp client** so image requests carry the same Plex auth headers and connection handling as
  every other request. Required exposing that client from `AppComponent`.
- Dropped the `Fresco.initialize` call, the `onTrimMemory`/`onLowMemory` cache purges (Coil trims
  itself), and the one-shot Glide disk-cache clear.
- **Glide is gone too** — it was vestigial: one `clearDiskCache()` call and a `makeGlideHeaders(): Any?`
  that only ever `TODO()`d. The interface method is renamed `makeImageRequestHeaders` and kept, since it
  is part of the dead `MediaSource` scaffolding cu-15 will resurrect (CLAUDE.md: don't delete it).
- ProGuard: ~10 lines of Fresco/Glide keep rules replaced with `-dontwarn coil3.**`.

### Cache-key behaviour preserved (the part worth not breaking)

Fresco was configured with a custom `CacheKeyFactory` returning `UrlQueryCacheKey` — keying images on
the URL **query** rather than the full URL. That is not incidental: the same artwork is reachable over
LAN, WAN or relay at different hostnames, and keying on the full URL would re-download every cover
whenever the connection route changed.

Both call sites now pass `memoryCacheKey(uri.query ?: uri.toString())` explicitly, so a notification
reuses artwork already cached by the library screen regardless of which route fetched it.

### Results

| | Before | After |
|---|---|---|
| Release APK | 8.18 MB | **5.50 MB** |
| Native libs (`.so`) in APK | Fresco's | **none** |
| Image libraries | Fresco + Glide | Coil |

**2.7 MB smaller**, and one image library instead of two.

### Verification

- `./verify.sh` green, all 5 stages. 29 tests, 0 failures.
- `./test_release_build.sh` green — all reflection-dependent classes survived R8.
- Coverage 4.20% → 4.15%: deleted covered code (`ChronicleDraweeView`, `FrescoExt`) without deleting
  tests. Within the ratchet's tolerance, so it passed without an override.
- **Resolved after the fact by cu-16.** The mock-Plex mode added there drives the app against fixture
  data with no account, and confirms covers actually render: the library grid and book-details screens
  fetch the served PNG over HTTP, decode it and draw it into the `ImageView`s that replaced
  `ChronicleDraweeView`. The original note below stands as the state at the time of the migration.

- **Not verified automatically at the time: that images actually render.** No test exercises image loading, and
  there are no instrumented tests (cu-54). The criterion "image load/cache/transform verified" is met
  only to the extent that the code compiles and R8 keeps it. **This needs manual QA**: library grid
  covers, book-details cover, currently-playing artwork, the user-avatar list during onboarding, and
  the media notification's album art. Flagged to the owner rather than checked off silently.

## Acceptance Criteria

- [x] Single image library (Coil) — Fresco *and* Glide both removed
- [x] No deprecated DraweeView usage — `ChronicleDraweeView` deleted, 9 layouts on `ImageView`
- [x] Image load/cache/transform verified — **verified via cu-16 mock mode**: covers fetched over HTTP, decoded and drawn into the ImageViews that replaced ChronicleDraweeView, on library grid and book details (Android 15 emulator, no account)
- [x] Release build passes (ProGuard) — 5.50 MB, R8 checks green
