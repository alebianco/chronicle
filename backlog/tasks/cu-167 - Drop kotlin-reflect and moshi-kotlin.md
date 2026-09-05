---
id: cu-167
title: 'Drop kotlin-reflect and moshi-kotlin from the production APK'
status: Done
assignee: []
created_date: '2026-09-05'
updated_date: '2026-09-05'
labels:
  - R2
  - trust
  - debt
milestone: m-2
dependencies: []
priority: medium
ordinal: 1000
---

## Description

Found during the R2 dependency audit (2026-09-05).

`kotlin-reflect` ships in the production APK and **production code does not use it**. Verified:
zero `import kotlin.reflect` under `app/src/main`, and `AppModule.moshi()` is a bare
`Moshi.Builder().build()` with no `KotlinJsonAdapterFactory`. Every reflection use is in
`app/src/test`.

The justification in `app/build.gradle.kts:199-201` — *"Moshi runs in reflection mode (no codegen),
so all @JsonClass models need this at runtime"* — is **false**, and was invalidated by cu-62 when
the reflective adapter was removed. It also contradicts line 178 of the same file, which correctly
notes the move to codegen. Per CLAUDE.md's rule ("if this file contradicts the code, the code wins
— then fix this file"), the comment must be corrected in whichever change acts on this.

Root cause: the version catalogue declares `com.squareup.moshi:moshi-kotlin` (line 65), the
**reflection** artifact, whose POM hard-depends on `kotlin-reflect` and pins it to **1.8.21** —
which is why the explicit 2.2.10 pin exists at all. A codegen-only app wants plain
`com.squareup.moshi:moshi`.

Switching drops both `moshi-kotlin` and the explicit `kotlin-reflect` pin, removing roughly 1845
`kotlin.reflect.jvm.internal` classes that the ProGuard rules already comment on. Tests that need
reflection take it as `testImplementation`.

## Implementation Notes

The catalogue's `moshi` alias pointed at `moshi-kotlin` — the *reflection* artifact — whose POM
hard-depends on `kotlin-reflect`. Production is codegen-only since cu-62, so the alias now points at
plain `com.squareup.moshi:moshi`, and `moshi-kotlin` plus `kotlin-reflect` moved to
`testImplementation` under a new `moshi-kotlin-reflect` alias (tests do genuinely build adapters for
types with no `@JsonClass`).

**Measured, not assumed.** `:app:dependencies --configuration releaseRuntimeClasspath` reported
`kotlin-reflect` three times before and **zero** after. The release APK went from 7,016,700 to
6,792,992 bytes — **218 KB smaller**. `./test_release_build.sh` passes its R8 assertions: all
reflection-dependent classes survive in the dex. (Its step 3 install failed on `more than one
device/emulator`, which is environmental; the build and dex checks are the parts that matter here.)

The false comment claiming Moshi ran in reflection mode is gone, along with a second stale line
("Moshi will use reflection-based adapters instead") directly under the codegen declaration.

## Acceptance Criteria

- [x] Catalogue declares `com.squareup.moshi:moshi`, not `moshi-kotlin`
- [x] The explicit `kotlin-reflect` production dependency is gone; tests declare it themselves
- [x] The false comment at `app/build.gradle.kts:199-201` is corrected
- [x] `./test_release_build.sh` passes — Moshi adapters are reflection-adjacent and R8-sensitive
- [x] APK/dex size change recorded in the closing notes
