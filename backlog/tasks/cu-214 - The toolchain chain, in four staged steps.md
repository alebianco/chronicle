---
id: cu-214
title: "The toolchain chain, in four staged steps"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - debt
  - tooling
milestone: m-3
dependencies:
  - cu-210
  - cu-211
priority: high
---

## Description

Four version bumps that **must happen in this order**, because each unlocks the next. One ticket, but
**four separately committed and separately verified steps** — the sequencing is the safety, so it
lives in the acceptance criteria rather than being left to judgement.

decision-22 records the gate the last two clear:

> **Compose BOM held at the 2026.06.x line.** 2026.08.00 pulls Compose 1.12.0, whose
> `material-ripple-android` requires **compileSdk 37**; this project is on 36.
>
> **`lifecycle-*-compose` reuse the existing 2.10.0 ref.** 2.11.0 wants compileSdk 37 *and* AGP 9.1.

## Step 1 — Room 2.8.1 → 2.8.3

**Room's KMP support landed in 2.8.3.** cu-194 §5 asked for exactly this check — *"confirm against
the Room release notes before relying on it — it is the kind of version-dependent claim this repo has
been burned by"* — and the belief was wrong about the version, not just unverified. It is 2.8.3, not
the 2.7 line.

Beyond being current, it **settles SQLDelight**: cu-194 raises SQLDelight only in case Room could not
go multiplatform. At 2.8.3 it can, so SQLDelight is declined on the merits.

**Not Room 3.0.** `androidx.room3` is a deliberate breaking major, currently alpha, whose headline is
JS/WASM — against five databases, nineteen exported schemas and seven migration tests, with no second
target asked for by cu-182. Revisit when cu-182 names one *and* it is stable.

**The migration tests are the safety net, so they must run for real** — `--rerun-tasks`. Gradle's
up-to-date checks have already made a passing suite meaningless here twice (cu-204's stale coverage
report; sabotage verification generally). Room's codegen also moved to Kotlin output in the 2.8 line,
so check the generated `_Impl` classes still match what `test_release_build.sh` asserts and what the
JaCoCo exclusions catch.

## Step 2 — Kotlin 2.2.10 → 2.3.11, with KSP

**Kotlin 2.4 is a hard ceiling, not caution.** Measured: Kotlin 2.4.20 is published, but **KSP's
newest release is 2.3.11 — there is none for Kotlin 2.4**, and Room, Hilt, Moshi *and* Ktorfit all run
through KSP. Nothing here can outrun it.

That also fixes **Ktorfit at 2.6.5**, whose 2.7.5 needs stdlib 2.4.0. When that was attempted it
produced nine failures that never mentioned Ktor — four `[MissingType]: Element 'Audiobook'`, a Room
`BookDatabase` failure, four Hilt errors citing `error.NonExistentClass` for a class that resolved
fine. **Raising the stdlib under KSP makes unrelated types vanish**, so the symptom points nowhere
near the cause. Remember that if this step misbehaves the same way; bisect rather than guess.

Everything generating code moves together or not at all.

## Step 3 — compileSdk 37 + AGP 9.x

**The riskiest change in cu-210's programme.** AGP 9.4.0 is published, so the gate can be cleared.

AGP 8 → 9 is a major: it touches every build file and can change DSL, packaging, lint behaviour and
R8 defaults. It can break the build in ways no unit test observes — three of the four real defects
found while migrating to Ktor were invisible to 1,678 green tests, and a toolchain major is the same
shape of risk, larger.

Things known to matter:

- **`kotlinOptions` is already migrated** to `compilerOptions` (done during the Ktor work, because
  the Ktorfit plugin escalated that deprecation to an error). One fewer AGP 9 item.
- **`lint-baseline.xml` will move.** It is 6,097 lines and already notes it was created under a
  different variant. A shrinking baseline is good news; a growing one is something to read.
- **`InvalidPackage` is disabled** for `ktor-utils-jvm` (`java.lang.management` from a desktop-only
  debug helper). Confirm AGP 9 still needs that.
- **R8 and the release build.** `test_release_build.sh` asserts reflection-dependent classes survive,
  and those assertions exist because this has broken before.
- **`minSdk` stays 27** (decision-3). Nothing here licenses raising it.

**No other library version moves in this step.**

## Step 4 — Compose BOM + lifecycle 2.11

The payoff. And **a Compose BOM bump is a UI change**, which this codebase learned the hard way — the
migration recorded four defect classes only a device showed, all invisible to a green Compose suite:
a `_white` drawable with a black fill needing an explicit `tint`; a `ComposeView` clipped by a View
parent; `Icon` flattening a two-colour drawable so a play button shipped as a bare circle; and
Material3's `labelLarge` not uppercasing, so `textAllCaps` section titles silently lost their casing
— caught only by comparing against a screenshot taken *before* the change.

That last one is the method to repeat: **screenshot before, screenshot after, compare.** A Material3
minor can move type scales, ripple and default paddings with no test noticing. `ChronicleThemeTest`
pins the palette; type and spacing are not pinned.

## Acceptance Criteria

**Step 1 — Room, committed alone**
- [x] Room 2.8.3 across `room-runtime`, `room-ktx`, `room-compiler` — one `room` version ref, so
      all three move together
- [x] All **nineteen** exported schemas unchanged — regenerated with `kspDebugKotlin
      --rerun-tasks` and byte-identical to the committed copies (`git diff --quiet app/schemas/`)
- [x] The migration tests pass with `--rerun-tasks`, not from cache — **26 of them**, not the
      seven this ticket claimed: 4 in `RoomMigrationTest` and 22 in `RoomSchemaTest`. 42 Gradle
      tasks executed, none up to date
- [x] `./test_release_build.sh` finds every reflection-dependent class — 9,145 classes in dex, all
      survived R8. Its later install step fails, but that is pre-existing (see below)
- [x] SQLDelight recorded as declined in cu-194, now citing a 2.8.3 that is actually in the build
      rather than a release note

**Step 2 — Kotlin + KSP, committed alone**
- [ ] Kotlin and KSP on the 2.3.11 line, bumped together
- [ ] Every KSP processor still generates — Room `_Impl`s, Hilt components, Moshi adapters, Ktorfit
      service impls — confirmed by their **existence**, not by a green compile
- [ ] Whether Ktorfit can now move past 2.6.5 is checked and recorded **either way**
- [ ] The KSP ceiling written down where the next person looks, so Kotlin 2.4 is not attempted again

**Step 3 — compileSdk 37 + AGP 9, committed alone and device-verified**
- [ ] `compileSdk = 37`, AGP 9.x, `minSdk` still 27
- [ ] **Device-verified**: installed, launched, library loads, playback starts, a download completes.
      A toolchain major is precisely where a green suite is not evidence
- [ ] Both orientations on at least the player
- [ ] `lint-baseline.xml` movement reviewed rather than regenerated blindly; the diff summarised
- [ ] The `InvalidPackage` suppression re-justified or removed
- [ ] **No library version other than AGP, the Gradle wrapper and compileSdk moves**

**Step 4 — Compose BOM + lifecycle, committed alone**
- [ ] Both moved, versions recorded
- [ ] **Before-and-after screenshots** of player, library, home, details and settings, in **both
      orientations**, compared rather than merely collected
- [ ] Ripple, type scale and section-title casing specifically checked — the recorded failure modes
- [ ] `ChronicleThemeTest` and the Compose screen suites green

**Throughout**
- [ ] `./verify.sh` green after **each** step, not only at the end
- [ ] decision-22 updated once step 3 lands: its two "held" notes are no longer current

## Step 1 result (2026-09-07)

**Landed, and it was uneventful — which is the good outcome for a dependency bump.** `room 2.8.1 ->
2.8.3`, one version ref covering runtime, ktx and compiler.

The schema check was the one that mattered, since a diff would have meant the bump changed generated
SQL. Regenerated under `--rerun-tasks` and **all nineteen are byte-identical** to the committed
copies. Room's codegen moved to Kotlin output in the 2.8 line, which was the reason to suspect drift;
it produced none here.

Two corrections to this ticket's own text, both in the safer direction:

- **It says "the seven migration tests". There are 26** — 4 in `RoomMigrationTest` and 22 in
  `RoomSchemaTest`. All green with 42 Gradle tasks executed and none up to date.
- **`./test_release_build.sh` fails at its install step, and did so before this change too.** Proved
  by stashing the bump and re-running: identical failure on 2.8.1. The APK it builds is
  `app-release-unsigned.apk`, and an unsigned APK can never install
  (`INSTALL_PARSE_FAILED_NO_CERTIFICATES`). The script exits 0 when no device is attached, so this
  only surfaces when one is — which is why it has gone unnoticed. **Filed as cu-224.** The criterion
  this step needed passed at step 2b: 9,145 classes in dex, every reflection-dependent one survived
  R8.

## Notes

Closing status **In Review**. Steps 3 and 4 both want the owner's eye — one is a toolchain major with
device evidence, the other is a judgement about how screens look — and step 3 edits decision-22.

**If step 3 needs changes beyond build files** — a source change forced by a DSL removal, say — stop
and split it out. A toolchain bump that starts editing app code is two pieces of work wearing one
hat.

Depends on cu-211 deliberately: the launch-smoke test is the device-level safety net that makes the
AGP 9 step safe to attempt.
