---
id: cu-57
title: Mutation testing pilot (PIT) after cu-44
status: Draft
assignee: []
created_date: '2026-08-30'
labels: [R1, agentic]
dependencies: [cu-44]
priority: medium
---

## Description

Evaluate test *quality* (do tests actually fail when behaviour breaks?) rather than test *coverage*
(was the line executed?). The cu-3 ratchet cannot see the difference — cu-56 is a live example of code
that contributes coverage while verifying nothing.

Owner decision 2026-08-30: **deferred until after cu-44.** Researched and scoped now so the groundwork
isn't repeated.

### Why defer rather than do it now

Not because PIT would be too noisy — `targetClasses` scopes it to whichever classes you choose, so an
allowlist of tested classes is trivial. The real reason is that mutation testing measures the quality
of tests that *exist*, and at 3.76% coverage across 131 source files there is almost nothing to
measure. The dominant problem is absent tests, and PIT does not help write them. After cu-44 backfills
the R1 risk surface there is a suite worth auditing.

### Decisions already made (don't re-litigate)

- **Plugin: `pl.droidsonroids.pitest` 0.2.27** (Apache-2.0), *not* mainline `info.solidsoft.pitest`.
  Solidsoft explicitly refuses Android — it is built on `JavaPlugin` and needs `sourceSets.main`/`test`,
  which `com.android.application` does not provide. The fork is actively maintained (0.2.21 fixed
  AGP 8.8+; 0.2.26 handled Gradle 9) and generates a `pitestDebug` task with the classpath already
  wired, including AGP's mockable android.jar.
- **Arcmutate is rejected.** It is the only tool with working Robolectric support and proper Kotlin
  handling, but it is proprietary and licence-gated — excluded by D12 rule 7 (no proprietary SDKs),
  independent of cost. Do not revisit unless that rule changes.
- **Robolectric tests must be excluded from scope.** PIT + Robolectric is broken and unfixed:
  koral--/gradle-pitest-plugin#80 (open since 2022), #58 (2020), upstream pitest#1065 (closed
  2026-08-17 by pointing at Arcmutate, not by a fix). It fails *silently*, reporting false
  `SURVIVED`/`NO_COVERAGE`. Running PIT over `RoomMigrationTest` would report our verified-good
  migration tests as bad. This is the single most important constraint here.
- **Kotlin noise**: use `mutators = DEFAULTS` (not `STRONGER`/`ALL`) and
  `avoidCallsTo = kotlin.jvm.internal` to suppress `Intrinsics.checkNotNull*` mutants. The free
  `pitest/pitest-kotlin` filter was archived 2023-03-01. `pitest-descartes` (Apache-2.0, maintained) is
  a legitimate fallback if Gregor's Kotlin noise proves unbearable.

### Starting config

```kotlin
pitest {
  pitestVersion.set("1.22.1")
  mutators.set(listOf("DEFAULTS"))
  avoidCallsTo.set(listOf("kotlin.jvm.internal"))
  targetClasses.set(listOf(/* allowlist: only classes with real tests */))
  threads.set(4)
  timestampedReports.set(false)
}
```

Start with `features.player.TrackListStateManager` (pure JVM, no Android types, 0.012s suite) plus
whatever cu-44 adds that is *not* Robolectric-based. Never mutate generated code — an allowlist
`targetClasses` avoids needing exclusions for `*_Impl`/`Dagger*`/`*Binding`/`*_Factory`.

### Scope boundary

Keep PIT **local/manual — out of `verify.sh` and out of CI.** No mutation-score threshold until there
is a suite worth gating on; a threshold added early just blocks unrelated PRs. Revisit gating as a
separate decision. Note `withHistory` needs a preserved workspace between builds, which GitHub Actions
does not provide by default.

### Context

This task exists because the owner noticed agents hand-sabotaging code (breaking a migration, breaking
a unit test) to prove tests could fail — see cu-3 and cu-1 implementation notes. That is manual
mutation testing; it works but only covers the handful of spots someone thinks to poke. Automating it
is the right instinct, just better spent once there is a suite to point it at.

## Acceptance Criteria

- [ ] PIT runs via `./gradlew pitestDebug` on an allowlist of genuinely-tested, non-Robolectric classes
- [ ] Mutation score reviewed; surviving mutants either killed with new assertions or justified
- [ ] Robolectric-based tests explicitly excluded, with the reason recorded in the config
- [ ] Remains out of `verify.sh`/CI unless a separate decision adds a gate
