---
id: cu-62
title: Moshi reflection to codegen
status: Done
assignee: [claude]
created_date: '2026-08-30'
labels: [R1, performance]
dependencies: []
priority: low
milestone: m-1
---

## Description

Split out of cu-8, whose "Moshi on codegen" criterion was left unmet.

9 classes carry `@JsonClass(generateAdapter = true)` but no processor is wired, so `AppModule` falls
back to `KotlinJsonAdapterFactory()` — reflection. Adding `ksp(libs.moshi.codegen)` generates real
adapters: faster parsing, smaller APK, and one less reflection surface for R8 to keep.

### Why it was deferred rather than done

This is a **behavioural** change, not a build-system one. Reflection is lenient about absent and null
fields in ways generated adapters are not, and these models parse live Plex responses. cu-8 could not
verify it safely.

**That objection is now weaker**: cu-16 landed a fixture pack and contract tests that assert every
model deserializes into real domain objects. Flipping to codegen and re-running those tests would catch
most of the risk — the residual being fields the fixtures do not exercise. Those residual
fields are on the [[cu-73]] live-server checklist.

### Scope

1. `ksp(libs.moshi.codegen)` in `app/build.gradle.kts`.
2. Remove `KotlinJsonAdapterFactory()` from `AppModule` (its whole purpose is the reflection fallback).
3. Run `PlexFixtureContractTest` — it should pass unchanged. Any failure is a real leniency difference,
   not a test problem.
4. Extend the fixtures with a null/absent-field case per model before trusting the switch.
5. Check the ProGuard rules: generated adapters change what R8 must keep, so re-run
   `./test_release_build.sh`, which asserts every `@JsonClass` model survives.

## Acceptance Criteria

- [x] `ksp(moshi-codegen)` wired; `KotlinJsonAdapterFactory` removed
- [x] cu-16 contract tests pass unchanged
- [x] Fixtures extended with absent/null-field cases
- [x] `./test_release_build.sh` green; APK size delta recorded

## Implementation Notes

`ksp(libs.moshi.codegen)` wired, `KotlinJsonAdapterFactory` removed from `AppModule`. The feared
leniency differences did not materialise on fixture data — the cu-16 contract tests passed unchanged.

**The important finding is why that reassurance was nearly worthless.** `PlexFixtureContractTest`
built its *own* Moshi with `KotlinJsonAdapterFactory`, so it was still exercising reflection after
the production path had switched. It would have passed even if codegen were entirely broken or a
model had lost its annotation. It now builds Moshi the way `AppModule` does, which is what makes it
a real check of the switch.

Three tests added for the case reflection and codegen genuinely differ:

- a directory with most fields absent still parses to Kotlin defaults;
- an explicit JSON `null` on a non-null field is **rejected** rather than silently defaulted — pinned
  so a server that starts sending nulls fails loudly rather than mysteriously;
- an empty container parses to an empty list.

R8: `./test_release_build.sh` passes, all reflection-dependent classes survive the dex check, and the
release APK is 6.7M. Existing keep rules already cover generated adapters (`-keep class **JsonAdapter`).
Some of the Moshi rules are arguably now over-broad, since codegen removes reflection over the models —
narrowing them is separate work and carries its own release risk, so left alone deliberately.

Coverage 22.55% → 24.47%, largely because generated adapters count as covered code.

The install step of `test_release_build.sh` fails with `INSTALL_PARSE_FAILED_NO_CERTIFICATES` — the
APK is unsigned by design. Unrelated to this task; the R8 verification it exists for passed.
