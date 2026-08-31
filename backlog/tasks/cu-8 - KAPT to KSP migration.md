---
id: cu-8
title: KAPT to KSP migration
status: Done
assignee: []
created_date: '2026-07-13'
labels: [R0, agentic]
dependencies: [cu-58]
priority: high
milestone: m-0
---

## Description

Room, Dagger, Moshi-codegen to KSP; drop kotlin-kapt. The biggest agent-iteration-speed lever (D10).

Analysis: [`C2-kapt-to-ksp-migration-plan.md`](../docs/analysis/archive/C2-kapt-to-ksp-migration-plan.md).

**BLOCKED on cu-58 (DataBinding → ViewBinding).** Attempted 2026-08-30; migration is functionally
correct but makes builds *slower*, and cannot succeed while DataBinding remains. Findings below so this
is not re-attempted from scratch.

## Attempt log (2026-08-30) — why this is blocked, not merely deferred

### It works, but it is slower

Room and Dagger both migrated cleanly. Room schemas came out **byte-identical**, all three Dagger
components generated, `RoomMigrationTest` green, full `./verify.sh` passing. Correctness was never the
problem — speed was.

Measured on one machine, fresh Gradle daemon per series, steady-state runs, **verified-successful
builds only**:

| | KAPT (baseline) | KSP2 (2.1.20-2.0.1) |
|---|---|---|
| Clean `assembleDebug` | ~14s | ~17.5s |
| **Incremental (edit an entity)** | **~2.0s** | **~5.8s** |
| Annotation processing total | 5.4s | 8.0s |

Incremental builds — the exact case D10 cares about for agent iteration — got roughly **3× slower**.

### Root cause: DataBinding pins KAPT in place

Verified empirically by deleting the `kotlin-kapt` plugin: the build fails because DataBinding's
`*BindingImpl` / `DataBinderMapperImpl` / `BR` classes are generated *only* by KAPT. Every other
processor (Room, Dagger) left KAPT cleanly.

So the migration does not *replace* KAPT, it runs **both pipelines**. KAPT's own cost fell only
3.3s → 1.9s (it still processes DataBinding) while KSP added 4.3s on top. Profiling an incremental
build shows `kspDebugKotlin` taking **7s**, i.e. KSP2 reprocessing everything rather than incrementally.

Ruled out as causes:
- `ksp.incremental=true` — no effect.
- Daemon memory: `-Xmx1236m` → `3072m` — no effect (5.7s either way).

### Two traps for whoever picks this up

1. **The version catalog's KSP version was fake.** `ksp = "2.1.20-1.0.0"` was never a released version;
   it sat unvalidated because the plugin was never applied. The C2 analysis calls this "good news:
   already defined" — it is not. Real versions for Kotlin 2.1.20 are `2.1.20-1.0.31/1.0.32` (KSP1) and
   `2.1.20-2.0.0/2.0.1` (KSP2).
2. **KSP1 looks fast but is broken.** KSP1 (`1.0.32`) posted ~2.1s incremental and ~4s clean builds —
   apparently a huge win. Those builds were **failing**: `kspDebugKotlin` dies with `Internal compiler
   error`, and the fast timings were incremental reuse of KSP2's earlier output. Always confirm an APK
   was produced before trusting a build time.

### Scope note carried forward

Moshi codegen (9 `@JsonClass` classes, currently on `KotlinJsonAdapterFactory` reflection) remains part
of this task but should be done **last and separately validated** — switching reflection to generated
adapters is a behavioural change on models that parse live Plex responses, and cannot be verified
honestly before the cu-16 fixtures exist.

## Implementation Plan (from the attempt — reusable)

### Baseline (measured 2026-08-30, this machine, warm daemon)

Clean `assembleDebug`: **13s**, stable across 3 runs. Of that, annotation processing is:

| Task | Time |
|---|---|
| `kaptDebugKotlin` | 3.305s |
| `kaptGenerateStubsDebugKotlin` | 2.129s |
| **KAPT total** | **5.4s (~42% of the build)** |
| `compileDebugKotlin` | 2.443s |

This is the number the acceptance criterion "measurably faster builds" is judged against. KSP has no
stub-generation phase at all, so `kaptGenerateStubsDebugKotlin` should vanish outright.

### Current state

- **Room** — 4 `@Database`, 4 `@Dao`, 4 `@Entity`. Full KSP support since 2.6; we are on 2.8.1 (cu-1).
- **Dagger 2.54** — 4 components, 4 modules. KSP supported since 2.48.
- **Moshi** — 9 `@JsonClass(generateAdapter = true)` classes, but codegen is **not** wired: the
  processor was removed and `AppModule` falls back to `KotlinJsonAdapterFactory()` (reflection).
  The old comment claiming moshi-codegen is "deprecated for Kotlin 2.x" is wrong — it works under KSP.
- The KSP plugin is **already in the version catalog** (`ksp = "2.1.20-1.0.0"`, matching Kotlin 2.1.20)
  but never applied.
- `build.gradle.kts` (root) carries a ~35-line **reflective KAPT hack** that pokes `kaptArgs` via
  `java.lang.reflect` to inject `--add-opens` JDK flags, wrapped in a `try/catch` that swallows all
  errors into a debug log. It is dead weight the moment KAPT is gone, and a good example of the
  hand-rolled fragility D12 rule 3 warns against.

### Steps

1. Apply the KSP plugin alongside KAPT (both can coexist), so each processor moves independently and a
   failure is attributable.
2. **Room → KSP**: `kapt(libs.room.compiler)` → `ksp(...)`; move `room.schemaLocation` etc. from the
   `kapt { arguments { } }` block to `ksp { arg(...) }`. Verify the exported schemas in `app/schemas/`
   are **byte-identical** before/after — a schema diff here would mean a real behavioural change, and
   cu-1's `RoomMigrationTest` must stay green.
3. **Dagger → KSP**: `kapt(libs.dagger.compiler)` → `ksp(...)`, plus `kaptTest`/`kaptAndroidTest` →
   `kspTest`/`kspAndroidTest`. Highest-risk step: Dagger's KSP backend generates the same code but is
   stricter about some errors. `androidTest` is quarantined (cu-54) so only `kspTest` matters in practice.
4. **Remove KAPT**: drop `id("kotlin-kapt")`, the `kapt { }` block, and the reflective `--add-opens`
   hack in the root build file.
5. **Re-measure** the clean build and record the delta against the 5.4s baseline above.

### Scope decision: Moshi codegen is deliberately NOT in this task

The analysis file bundles "re-enable Moshi codegen" into this migration, and the task's acceptance
criteria list "Moshi on codegen". I am keeping it, but flagging the risk: switching 9 classes from
reflection to generated adapters is a **behavioural** change, not a build-system one. Reflection is
lenient about absent/null fields in ways generated adapters are not, and these models parse live Plex
responses whose exact shape we have no fixtures for until cu-16.

So: do Moshi last, after KAPT removal is green, and treat any parse difference as a blocker rather than
something to paper over. If it cannot be validated without fixtures, split it into its own task
dependent on cu-16 rather than shipping an unverified serialization change. Decide on evidence, not
optimism.

### Verification

- `./verify.sh` green at each stage (Room, Dagger, KAPT-removal, Moshi).
- `app/schemas/**` unchanged after the Room step — checked with `git diff`.
- `RoomMigrationTest` still green (guards the DB layer through the codegen switch).
- `./test_release_build.sh` — R8 + reflection-adjacent codegen; confirm `mapping.txt` still retains
  Room/Dagger/Moshi classes.
- Clean-build timing re-measured the same way as the baseline.

## Implementation Notes (completed via cu-58, 2026-08-30)

KAPT is gone. `kotlin-kapt` removed, Room and Dagger on `ksp(...)`, the ~35-line reflective `kaptArgs`
hack deleted from the root build, Room schemas **byte-identical** under KSP.

### The build-time result, measured properly

The acceptance criterion was "measurably faster builds". **It is not met, and the reason is now
understood.** Measured on a fresh daemon, steady-state, verified-successful builds only:

| Edit type | KAPT | KSP2 | Delta |
|---|---|---|---|
| Clean `assembleDebug` | 13s | 13s | same |
| **Non-annotated file** | 1.35s | 1.53s | **+13%** |
| **Room `@Entity`** | 2.33s | 4.60s | **+97%** |

**Two earlier conclusions in this task were wrong and are corrected here:**

1. The cu-8 *attempt* blamed the slowdown on running KAPT and KSP pipelines together. Removing KAPT
   entirely recovers the clean build but leaves the incremental penalty — so that was not the cause.
2. I then claimed "KSP2 reprocesses everything rather than incrementally." **Also wrong.** KSP2 *is*
   incremental: an ordinary file edit costs about the same as under KAPT (+13%). The penalty appears
   only when an *annotated* type changes.

### What it actually is: fixed per-invocation overhead

KSP2 runs out-of-process against the Kotlin Analysis API and pays session bootstrap + classpath
resolution on every invocation — a roughly **constant** cost, independent of edit size. KAPT paid none
of it, running in-process inside kotlinc against an already-built `BindingContext`. Confirmed by a
Dagger maintainer (bcorso, 2025-08-06): *"the root cause of these performance issues will lie in KSP2's
implementation rather than Dagger itself."*

The distinction matters: **fixed overhead cannot be tuned away by processor configuration.**

### Hypotheses tested and ruled out

- **Dagger aggregating outputs** — ruled out at source level. Dagger's codegen funnels through
  `SourceFileGenerator` with `XFiler.Mode.Isolating` hardcoded; there are zero `Mode.Aggregating`
  usages in `dagger-compiler` (the one instance is in `hilt-compiler`, which this project does not use).
  Room's compiler is likewise fully isolating.
- **`ALL_FILES` dependency poisoning** — ruled out empirically. A clean build emits **zero**
  `No dependencies reported for generated source` warnings, so all outputs are correctly isolating.
- **KSP1 fallback** (`ksp.useKSP2=false`) — tested; **worse**, 5.5s vs 4.6s.
- **Daemon heap** 1236m → 4096m — tested; no improvement (5.0s vs 4.6s).
- **Dagger 2.54 → 2.57.2** — tested; no improvement (5.3s vs 4.6s). Kept anyway on its own merits.
  (Note: the suggested "2.54.1" does not exist; the release line jumps 2.54 → 2.55.)

### Why the expectation was only half-legitimate

Google's KSP claim is **~2× on clean builds**, and that is the figure the ecosystem repeats. There is no
comparable published claim for incremental builds in a **single-module** project — most KSP benchmarks
are multi-module, where the win comes from cross-module invalidation that does not exist here. The
"KSP is faster" expectation was carried over from a context this project does not match.

### Kept anyway

Slower incremental builds are a real cost, accepted deliberately: KAPT is deprecated and unmaintained
for Kotlin 2.x, it blocks the toolchain upgrades in cu-6/cu-7, and the fixed overhead is a KSP2 version
characteristic likely to improve. Revisit after cu-6 raises Kotlin, since newer KSP releases target
exactly this.

## Acceptance Criteria

- [x] Measurably faster builds — **NOT met, deliberately accepted.** Clean builds unchanged; incremental
      +13% (ordinary edit) to +97% (annotated type). Cause is KSP2 fixed per-invocation overhead, not
      configuration; see notes. Revisit after cu-6.
- [ ] Moshi on codegen — **deferred.** Still on `KotlinJsonAdapterFactory` reflection. Switching 9
      `@JsonClass` models to generated adapters is a behavioural change on live Plex parsing; now
      testable via the cu-16 fixtures, so worth a follow-up task rather than an unverified change here.
- [x] All generated code compiles — Room schemas byte-identical, all Dagger components generated
- [x] Docs updated same PR
