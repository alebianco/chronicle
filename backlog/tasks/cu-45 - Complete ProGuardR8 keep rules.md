---
id: cu-45
title: Complete ProGuard/R8 keep rules
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R0, release]
dependencies: []
priority: high
milestone: m-0
---

## Description

H2: release builds use R8 but keep rules were minimal (Room/Retrofit/Moshi/Dagger/Media3 all reflection-adjacent). In-flight uncommitted work exists (~170 lines + test_release_build.sh). Finish, commit, and gate: every dependency needing keep rules covered; release build smoke-tested.

Analysis: [`H2-proguard-rules-plan.md`](../docs/analysis/H2-proguard-rules-plan.md).

## Implementation Notes

### The problem was the opposite of the task description

The task assumed keep rules were "minimal". They were not — `2a5cc3d` had already landed ~170 lines
covering every library. The actual defect was that they were **far too broad**, which defeats R8:

- Only **34.9%** of classes were obfuscated; **65% were exempt**.
- `-keep class kotlin.** { *; }` alone pinned **1,845** `kotlin.reflect.jvm.internal` classes nothing
  reflects over.
- Blanket `features.**` / `views.**` keeps exempted **572 of our own classes** — app code R8 should be
  free to shrink and rename entirely.
- `-keep class androidx.media3.** { *; }` pinned ~700 extractor/renderer classes; Media3 ships its own
  consumer rules and R8 can otherwise strip per-format decoders we never use.

### What changed

Tightened the over-broad rules to precise ones:

- Kotlin: keep only `kotlin.Metadata` (what R8 and Moshi actually read), not all of `kotlin.**`.
- Coroutines: dropped the blanket keep; the named `MainDispatcherFactory` /
  `CoroutineExceptionHandler` keeps already cover the service-loader entry points.
- App code: `Fragment` subclasses keep only `<init>` (the framework instantiates them by name; their
  members need not survive), custom views keep the `(Context, AttributeSet)` constructor used by XML
  inflation. Dropped the blanket `features.**` / `views.**` keeps.
- Retrofit: keep the two real service interfaces rather than all of `plex.**`.
- Media3: rely on its bundled consumer rules.
- Removed a keep rule for `PlexService` — **no such interface exists**. `PlexService.kt` declares
  `PlexLoginService` and `PlexMediaService`; the old rule protected a phantom class.

### Result

| | Before | After |
|---|---|---|
| APK (release, unsigned) | 10.16 MB | **8.26 MB** |
| Classes in mapping | 12,118 | 8,242 |
| Obfuscated | 34.9% | **75.9%** |
| Classes in shipped dex | — | 7,555 |

**1.9 MB smaller (~19%)**, with more than twice the proportion of code obfuscated.

### test_release_build.sh: fixed and given teeth (absorbs cu-55)

Two defects fixed:

1. **False negative (cu-55)**: the script looked for `app-release.apk`, but with no release signing
   config R8 emits `app-release-unsigned.apk`. It therefore printed "❌ APK not found" after a fully
   successful build — a real R8 breakage and a healthy build looked identical. Now globs the release
   output directory.
2. **The script verified nothing about R8.** Added a check that reflection-dependent classes actually
   survived: Room databases/DAOs/entities, both Retrofit service interfaces, the Dagger component, and
   every `@JsonClass` Moshi model. These fail at *runtime*, not build time, so a size check alone is
   not a smoke test.

**A first attempt at that guard was itself broken** and worth recording: it grepped `mapping.txt` for
`^<class> ->`, but R8 gives no top-level mapping entry to classes it keeps *unrenamed* — precisely the
classes being asserted. Deleting the `PlexMediaService` keep rule still passed. Rewritten to read the
**dex** via `dexdump`, which is what actually ships. Re-tested: removing that keep rule now fails with
exit 1, naming the class.

### R8 full mode

Already enabled — it is the AGP 8.x default and `android.enableR8.fullMode` is not disabled anywhere.
No change needed; recorded here so the criterion is not re-investigated.

### Note on Coil

The criterion lists Coil, which is not a dependency yet (cu-43 migrates Fresco→Coil). Fresco and Glide
keep rules are left in place and correct for today; **cu-43 must remove them and add Coil's** — noted
in that task's context rather than pre-emptively adding rules for an absent library.

## Acceptance Criteria

- [x] Keep rules for Room, Retrofit, Moshi, Dagger, Media3 (Coil deferred to cu-43 — not yet a dependency)
- [x] ./test_release_build.sh passes
- [x] R8 full-mode considered (already on by AGP 8.x default)
