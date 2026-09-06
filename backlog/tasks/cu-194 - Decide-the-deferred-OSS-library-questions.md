---
id: cu-194
title: Decide the deferred OSS library questions
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - architecture
dependencies:
  - cu-187
  - cu-188
  - cu-185
priority: low
milestone: m-2
ordinal: 63000
---

## Description

Owner framing, 2026-09-06: this is a **project-wide** question, not a UI one.

Several library decisions were surveyed, deferred, or never asked at all, and they live as prose
inside closed tasks or review guides rather than as tracked work. Principle 3 says prefer a
maintained third-party library over hand-rolled code — but it says nothing about *when* to revisit,
so a deferral with no task attached becomes a permanent silence. This task gives each one an
explicit answer.

**Sequenced last in R2**, because several can only be judged against a codebase whose screens are
already Compose and whose DI has settled — hence the dependency on cu-187, cu-188 and cu-185.

## The rule for every candidate below

**Declining is the expected outcome, and a recorded "no" is a successful result.** The value here is
closing open loops, not adopting things. Three standing bars apply to anything adopted:

- **Licence** compatible with GPLv3 (Apache-2.0, MIT, BSD, MPL fine — check before adding).
- **No data extraction** ([[decision-19]]) — no analytics, telemetry, crash reporting or anything
  needing a cloud account, *whatever its licence*.
- **No forced toolchain jump** — [[decision-22]] pins the Compose BOM at the 2026.06.x line because
  newer needs compileSdk 37 and AGP 9.1. Anything dragging those is its own task.

## 1. State and presentation

- **Circuit** (slackhq, Apache-2.0) — deferred by cu-181 as *"Compose-only, so it is a non-starter
  today and a genuine option once screens are Compose."* After cu-188 that precondition is met. Its
  "screen = state + events, UI is a pure function of state" model is what `StateFlow` +
  `collectWhileStarted` already approximates. **Argument against:** it would replace a working
  pattern rather than fill a gap, and it brings its own navigation, colliding with the Navigation
  Compose assumption in [[decision-22]] and cu-188. Adoption needs a concrete defect it fixes.
- **Molecule** (cashapp, Apache-2.0) — turns a `@Composable` into a `StateFlow`. The question to
  answer with evidence: **do we have a ViewModel painful enough to want it?** `combineDistinct`
  (`util/FlowCombinators.kt`) plus the `stateIn` sharing-policy rules already cover what this
  codebase does.

## 2. Persistence — the one nobody has ever asked

**DataStore has never been mentioned anywhere in this repo.** Verified 2026-09-06: zero references
in `backlog/`, `CLAUDE.md`, the version catalogue or any source file. That is not a decision, it is
a gap — and it is the largest one here, because Google treats `SharedPreferences` as legacy while
**21 files** here depend on it.

This is *not* a recommendation to migrate. `SharedPreferences` is load-bearing in ways a swap would
have to preserve exactly, and several of them were learned expensively:

- **The credential split** (cu-108): `ChronicleAuth.xml` holds the three secrets and is excluded
  from Auto Backup while `Chronicle.xml` is not — enforced by two backup-rules files that
  `BackupRulesTest` parses.
- **`BACKUP_SETTING_KEYS`** gates export by key, and cu-189 is about to extend it.
- **`plex-session.sh`** reads and writes these XML files directly to swap real/mock sessions
  without `pm clear`. A storage change breaks the verification tooling, not just the app.
- **The cache-clobber trap**: a prefs file edited while the app runs is reverted on process death.
  DataStore's async writes have a *different* failure shape, not an absent one.
- **`util/PreferenceFlow.kt`** already provides the reactive read that is DataStore's main selling
  point here.

So the honest question is whether DataStore buys enough to justify touching the most
security-sensitive storage in the app. **Answer it explicitly — including "no, and here is why" —
so it stops being unasked.**

Also worth a look while in this area: **SQLDelight** vs Room (almost certainly no — Room is deeply
embedded across five databases with migration tests), and **kotlinx-serialization** vs Moshi (Moshi
codegen landed in cu-62 and works; note kotlinx-serialization is the KMP-friendlier of the two, so
it interacts with cu-182).

## 3. Testing

The declared-and-tracked ones are **not** in scope — `fragment-testing` (cu-178), `work-testing`
(cu-179) and Robolectric are already owned. The open questions are:

- **Turbine** (cashapp, Apache-2.0) — Flow testing. Weigh against `util/FlowTestExt.kt`, which
  already has `keepCollected`, `settledValue` and `settledValues` built for the exact traps this
  codebase hits. Probably redundant; say so if it is.
- **Screenshot testing** (Paparazzi or Roborazzi) — this is the interesting one, because
  [[decision-22]]'s whole case rests on four landscape/visibility bugs that unit tests could not
  see (cu-19, cu-68, cu-141, cu-142), and CLAUDE.md still requires device verification in both
  orientations for every migrated screen. A JVM screenshot test would catch that class *in the
  gate*. Assess after Compose lands, since both tools are far better against Compose than Views.
- **Kotest**, **Mockito** — almost certainly no. JUnit4 + MockK is established, and the MockK-vs-fake
  guidance is already written down. Record and move on.

## 4. Build and tooling

- **detekt** alongside ktlint — ktlint is formatting; detekt is complexity and code smells. Note
  the maintainability review measured cyclomatic complexity **by hand** to find cu-173/cu-174; a
  tool would have flagged `onCreateView` at CC 49 automatically. Weigh a complexity gate against
  gate runtime and false positives.
- **Gradle Dependency Analysis plugin** — would have found `media3-ui` (cu-192) and cu-167's
  `kotlin-reflect` automatically instead of by audit. Cheap and self-justifying if it works.
- **Pitest is adopted but not wired into `verify.sh`** (cu-57, a pilot). Decide whether it becomes
  part of the gate, stays a manual tool, or is removed — a plugin nobody runs is dead weight.
- **Gradle convention plugins** — single-module today, so this only matters if cu-182's Wear case
  or a second module ever lands. Note and defer.

## Explicitly out of scope

- **Hilt** — has its own task, cu-185.
- **Koin, Decompose, KMP** — gated on cu-182's multiplatform assessment.
- **Fetch2 replacement, Ktor** — cu-195.
- **Coil-Compose, Material3** — *already adopted* (`app/build.gradle.kts:152` and `:145`).
  `material3-adaptive` is a different artifact, is not declared, and belongs to cu-28.
- **kotlin-result** — the R2 review guide notes it is *"barely earning its place"* (7 imports across
  5 files, `Ok`/`Err` constructed in exactly one, alongside ~6 hand-rolled sealed outcome types).
  That is an *existing* dependency to scope or adopt more widely, not a new one to pick. Worth its
  own small task rather than bundling here.

## Acceptance Criteria

- [ ] Each candidate above gets an explicit adopt / decline, with reasoning — and for a decline,
      what would change the answer
- [ ] The **DataStore** question is answered rather than left unasked, with the `ChronicleAuth.xml`
      split, `BACKUP_SETTING_KEYS` and `plex-session.sh` constraints addressed either way
- [ ] Screenshot testing assessed specifically against the bug class in [[decision-22]] — the four
      landscape/visibility defects unit tests could not catch
- [ ] Pitest's status resolved: in the gate, manual, or removed
- [ ] Anything adopted is checked against licence, [[decision-19]], and the compileSdk 37 / AGP 9.1
      pin
- [ ] Outcomes recorded as an ADR where a choice is architectural; the task file suffices for a
      list of declines
- [ ] Any adoption lands as its own task, not inside this one — this task decides, it does not
      implement
