---
id: cu-167
title: 'Drop kotlin-reflect and moshi-kotlin from the production APK'
status: To Do
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

## Acceptance Criteria

- [ ] Catalogue declares `com.squareup.moshi:moshi`, not `moshi-kotlin`
- [ ] The explicit `kotlin-reflect` production dependency is gone; tests declare it themselves
- [ ] The false comment at `app/build.gradle.kts:199-201` is corrected
- [ ] `./test_release_build.sh` passes — Moshi adapters are reflection-adjacent and R8-sensitive
- [ ] APK/dex size change recorded in the closing notes
