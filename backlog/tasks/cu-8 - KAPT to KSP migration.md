---
id: cu-8
title: KAPT to KSP migration
status: Blocked
assignee: []
created_date: '2026-07-13'
labels: [R0, agentic]
dependencies: [cu-58]
priority: high
milestone: m-0
---

## Description

Room, Dagger, Moshi-codegen to KSP; drop kotlin-kapt. The biggest agent-iteration-speed lever (D10).

Analysis: [`C2-kapt-to-ksp-migration-plan.md`](../docs/analysis/C2-kapt-to-ksp-migration-plan.md).

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

## Acceptance Criteria

- [ ] Measurably faster builds
- [ ] Moshi on codegen
- [ ] All generated code compiles
- [ ] Docs updated same PR
