---
id: cu-62
title: Moshi reflection to codegen
status: Draft
assignee: []
created_date: '2026-08-30'
labels: [R1, performance]
dependencies: []
priority: low
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

- [ ] `ksp(moshi-codegen)` wired; `KotlinJsonAdapterFactory` removed
- [ ] cu-16 contract tests pass unchanged
- [ ] Fixtures extended with absent/null-field cases
- [ ] `./test_release_build.sh` green; APK size delta recorded
