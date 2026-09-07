# Tech stack

Truthful as of **2026-09-06**. Versions here are a convenience — `gradle/libs.versions.toml` and
`app/build.gradle.kts` are the authority. **If in doubt, check the build files, not this page.**

## Toolchain

| Item | Version | Notes |
|---|---|---|
| Kotlin | 2.2.10 | |
| Gradle | 9.5.1 | AGP 8.x **cannot** use Gradle ≥ 9.6.0 |
| AGP | 8.13.2 | AGP 9.x absorbs the Kotlin plugin — its own migration |
| minSdk | 27 | The API 27 managed device exists to catch ungated new APIs |
| target / compileSdk | 36 | cu-6 |

Single module `:app`. There are **no product flavors** — the old `freeAsInBeer` flavor no longer
exists. Release signing per `CONTRIBUTING.md`.

## Architecture and libraries

| Concern | Choice | Notes |
|---|---|---|
| Pattern | MVVM + Repository | |
| DI | Dagger 2.57.2 | Hand-rolled components. Hilt (cu-185) follows the screen migration |
| Persistence | Room 2.8.1 | **Five separate databases**; all export schemas and have migration tests |
| Network | Retrofit/OkHttp + Moshi | **codegen** (`@JsonClass(generateAdapter = true)`); the reflective `KotlinJsonAdapterFactory` was removed in cu-62 |
| Media | Media3 1.11.0 | ExoPlayer + MediaSession + Cast (cu-7) |
| State | StateFlow | LiveData removed in cu-52 |
| UI | **Compose** only | DataBinding removed in cu-58, ViewBinding in cu-206. Adopted by decision-22 (cu-181); the migration finished with the navigation shell. One deliberate `AndroidView` island: `CastButton` |
| Downloads | Fetch2 | |
| Annotation processing | **KSP, not KAPT** | cu-8/cu-58 — `kotlin-kapt` is gone; Room and Dagger use `ksp(...)` |

**Any doc claiming KAPT is wrong.** Note incremental builds are *slower* than under KAPT (+13% on
an ordinary edit, +97% when an annotated type changes) — this is fixed per-invocation overhead in
KSP2, not a misconfiguration. Ruled out: Dagger/Room aggregating outputs, `ALL_FILES` poisoning,
KSP1 fallback, larger daemon heap, newer Dagger. **See cu-8 notes before re-investigating.**

KSP build errors in generated code usually mean an annotation problem upstream — don't loop
blindly.

### Compose version pinning

The Compose BOM is held at the **2026.06.x** line and `lifecycle` at **2.10.0**, both because newer
versions demand compileSdk 37 (we are on 36) and AGP 9.1. **Raise them only with compileSdk.**

## The databases

| Database | Version |
|---|---|
| `BookDatabase` | 14 |
| `TrackDatabase` | 7 |
| `ChapterDatabase` | 3 |
| `CollectionsDatabase` | 3 |
| `BookmarkDatabase` | 1 |

None use `fallbackToDestructiveMigration`, deliberately. See the `room-and-persistence` skill.

## Build variants

**The debug build's package is `io.github.mattpvaughn.chronicle.debug`** —
`app/build.gradle.kts` sets `applicationIdSuffix = ".debug"` for the debug variant. The activity
class does **not** move with the suffix, so a component name must be fully qualified. See the
`device-verification` skill.

The debug and release source sets each provide their own `DebugHooks` object.
`DebugHooksContract` makes the compiler check the shape, but **only for the variant being built** —
which is why `verify.sh` compiles the release variant as its last stage (cu-70).

## Networking policy

**Cleartext HTTP is refused app-wide** (cu-42). `res/xml/network_security_config.xml` sets
`cleartextTrafficPermitted="false"` with **no exceptions**; a debug-only override in
`app/src/debug/res/xml/` adds loopback for the mock server. Plex serves LAN connections over HTTPS
via its `*.plex.direct` wildcard cert, so no LAN exception is needed.

**Trap:** `<domain>` matches by exact string or dot-boundary suffix only — it does **not** parse
CIDR. `10.0.0.0/8` builds without a warning and matches nothing, so a "LAN allowance" written that
way silently permits nothing and breaks LAN connections at runtime.

Also note resource shrinking renames the file in release (`res/8G.xml`), so verifying it in an APK
by its original path returns empty and **proves nothing**.

## Multi-backend scaffolding

Plex first; Audiobookshelf and local files/WebDAV planned (backlog D11).

`data/sources/MediaSource.kt`, `HttpMediaSource.kt`, `SourceManager.kt`,
`data/sources/local/LocalMediaSource.kt`.

**The ingestion seam is real since cu-80**: `SourceManager.refreshBooks` ingests per source through
`IBookRepository.ingest`, and `planIngestion` (`data/sources/IngestionPlan.kt`) decides what a
refresh writes and deletes.

**Still not *registered*** — `sources` is empty in production, so it is a no-op until cu-33.1 adds
one. cu-15 added the D11 capability flags (`hasNarrator`/`hasSeries`/`hasServerProgress`) and made
`SourceManager.refreshBooks` fail loudly instead of silently discarding fetches, but the fetch
methods on both `LocalMediaSource` and `PlexMediaSource` are still `TODO("Not yet implemented")` —
the live Plex work is in `PlexMediaRepository`.

## Tests

- **1651 unit tests** (`app/src/test/...`), including `RoomMigrationTest`, which drives the
  historical migration chains through real SQLite via **Robolectric** (Room's
  `MigrationTestHelper` is instrumented-only).
- **10 instrumented tests** on two managed emulators, which also run on an Automotive image.
- Coverage sits **backwards** — `data/model` above 80% next to `features/collections` and
  `features/home` at 0% — which is why the ratchet has a per-package gate.

## CI

`.github/workflows/ci.yml` — a single `verify` job that runs `./verify.sh` and uploads the APK,
test results and coverage report. **All build logic lives in `verify.sh`/Gradle, never in the
workflow** (D12 rule 6).

## Known debt

**29 files under `features/` import `data.sources.plex.*` directly** — task cu-80, dominated by
`PlexConfig` at 19 (a connection-state holder rather than a fetch API).

That count is **not pinned by any test and has drifted** — it read 27 in the docs until the
2026-09-06 audit measured 29. **cu-184** is to either ratchet it or stop quoting a number nothing
maintains.
