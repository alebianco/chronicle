---
id: cu-194
title: Decide the deferred OSS library questions
status: In Review
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
- **No forced toolchain jump** — [[decision-22]] pins the Compose BOM at the 2026.06.x line, and
  `lifecycle` and `navigation-compose` with it, because newer versions require **AGP 9.1.0**.
  compileSdk is already 37 and is **not** the constraint (decision-22's original note said it was;
  amended 2026-09-08 after measuring). Anything dragging AGP 9 is its own task.

## 1. State and presentation

> **Answered by [[decision-26]] (2026-09-08): Circuit, Molecule and Turbine are all ADOPTED, as one
> bundle.** They were never separable — Circuit without Molecule leaves state derivation as-is,
> either without Turbine leaves the tests awkward.
>
> cu-220 measured the boilerplate and recommended declining all three: the `*Destination` layer is
> 1,421 lines, of which only 94 (6.6%) are wiring Circuit removes, and Circuit's router would mean
> migrating navigation a third time. That became [[decision-25]], **which the owner vetoed.**
>
> The measurement was right and the yardstick was wrong. The case for Circuit is not fewer lines: a
> sealed event type with an exhaustive `when` makes an unhandled interaction a **compile error**,
> where `viewModel::method` makes it a method nobody calls — a failure this codebase has shipped
> twice (`download_all`, `MockPlexMode.disable()`). Staged as cu-229 (Turbine), cu-230 (Molecule),
> cu-231 (Circuit). The Turbine bullet in §3 below is covered by the same decision.

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

**Paging 3** — a genuine loose end. The archived `M7-large-library-performance-plan.md` proposed it
as a whole phase (*"Phase 3: Implement Pagination"*, with `androidx.paging:paging-runtime-ktx`) and
left an approval checkbox **unticked**: *"[ ] **Paging 3**: Approved to use Paging library"*. cu-51
then closed *without* adopting it, having found hand-rolled pagination already in production
(`BookRepository.refreshDataPaginated`). So it was surveyed, never approved, never declined, and the
plan was archived — the decline just needs recording. The measurement that justifies it is already
in hand: cu-51 measured 10,000 books searching in 29 ms and grouping in 1 ms, **~2x cost for a 2x
library on every path, nothing quadratic**, against a household library of 196 books.

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

## 5. Libraries that raise the portable share

Owner ask, 2026-09-06: include libraries that would **increase the multiplatform share of code**.

[[cu-182]] measured `app/src/main` at **23.7% portable** (7,710 of 32,508 lines). Its conclusion —
that KMP would share where sharing is least needed — is about *adopting KMP*, and stands. This is
the different, cheaper question: **which libraries raise that percentage as a side effect of work
we would do anyway, whether or not KMP is ever adopted?**

That framing matters. Nothing below is justified by multiplatform alone. Each has to earn its place
on Android first; the portability gain is a tie-breaker, exactly as cu-195 treats it. But it means
a "yes" here compounds, and picking the JVM-only option forecloses cu-182 quietly.

Read against cu-182's own breakdown of the unportable 74.2%, the movable slices are:

| slice | lines | % of Android-bound | candidate |
|---|---:|---:|---|
| other platform (Context, Uri, **prefs**) | 3,957 | 16.4% | DataStore (§2), Okio |
| lifecycle/ViewModel | 1,620 | 6.7% | Molecule (§1), `lifecycle-viewmodel` KMP |

UI (53.0%) and media/playback (10.1%) are not movable and should not be pretended otherwise — a
background media service and lock-screen transport get written twice, which is cu-182's point.
WorkManager (13.8%) has no KMP equivalent.

### Candidates, each also justified on Android

- **Okio** (Square, Apache-2.0) — `java.io.File` appears in **15 files**. Note the precise
  situation, because it is easy to overstate: `FrameworkFreeCoreTest` bans `android.*`/`androidx.*`
  imports, **not** JVM ones, so `java.io.File` does not by itself keep a file off that list —
  `MediaItemTrack.kt` is excluded because it imports `android.net.Uri`, and `java.io.File` would
  remain a portability blocker even after that was fixed. The guard is therefore *aligned with* this
  work rather than evidence for it. **Android justification independent of KMP:** Okio's
  `FakeFileSystem` makes file logic testable without a temp dir, and file handling is the
  highest-risk area in the app — four tasks (cu-85, cu-81, cu-153, cu-76) have failure modes that
  end in *deleted audio*. Weigh against cu-195, which touches the same paths; sequence them.
- **kotlinx-serialization** vs Moshi — already flagged in §2. Note the interaction: Moshi is
  JVM-only, so **every model stays Android-bound while it is the serializer.** Moshi codegen works
  and landed in cu-62, so this is not urgent; it is the single change that would move the most
  model code, and it is worth knowing that before another 20 models are written against Moshi.
- **kotlinx-datetime** — only if JVM date/time APIs actually appear in otherwise-portable code.
  Measure first; `util/DurationFormat.kt` is already pure over millis, which suggests this may be a
  non-issue. **Do not adopt on principle.**
- **Room is believed KMP-capable at our version** (2.8.1; support landed in the 2.7 line), which
  would mean SQLDelight is *not* required for portability. **Confirm against the Room release notes
  before relying on it** — it is the kind of version-dependent claim this repo has been burned by
  (cu-166's "Fetch2 is maintained"). Recorded because "KMP means SQLDelight" is the assumption §2
  might otherwise invite; if it holds, Room stays.
- **`lifecycle-viewmodel` KMP artifacts** — relevant only alongside Molecule (§1) and after
  cu-181/cu-188, since Compose Multiplatform is what makes a shared ViewModel useful at all.

### The thing to get right

**Do not let this become a KMP adoption by increments.** cu-182 owns that decision and is R4;
nothing here may pre-empt it, and no `commonMain` source set is created by this task. The test for
each candidate is: *would we choose this on Android alone?* If no, it does not go in. If yes, prefer
the KMP-capable option and record the portability delta.

Re-measure the 23.7% after any adoption, so cu-182 inherits a current number rather than this one.

## Answered — see cu-210 (2026-09-07)

**The owner worked through every candidate below.** The outcomes, their reasoning and the sequence to
deliver them live in **cu-210**, which spawned cu-211 … cu-220. This task closes by citing it rather
than duplicating it.

Three answers overturned what this task assumed, and each was **measured rather than reasoned**:

- **Room is KMP-capable — at 2.8.3, not 2.8.1.** This task said *"confirm against the Room release
  notes before relying on it."* Confirmed, and the belief was wrong about the version. So SQLDelight
  is declined on the merits (cu-214), and Room 3.0 is declined separately as an alpha breaking major
  with no consumer.

  **Since 2026-09-07 this is no longer a claim about release notes: the project is on 2.8.3.**
  cu-214 step 1 landed the bump, and all nineteen exported schemas regenerated byte-identical with
  26 migration and schema tests green under `--rerun-tasks`. SQLDelight is therefore declined
  against a version actually in the build — Room reaches the multiplatform target cu-182 might ask
  for, and replacing it would mean rewriting five databases, nineteen schemas and their migration
  tests to buy nothing.
- **Kotlin is capped by KSP, not by choice.** KSP's newest release is 2.3.11 and there is none for
  Kotlin 2.4, while Room, Hilt, Moshi and Ktorfit all run through it. That also fixes Ktorfit at
  2.6.5 (cu-214).
- **`play-services-oss-licenses` is declared, its plugin applied, and nothing uses it** — and it is a
  Play Services dependency, so it blocks the F-Droid distribution decision-1 puts first. The licences
  page gets built with a libre tool and the GMS dependency goes (cu-216).

Two candidates were **adopted after pushback**, having been declined in the first pass:

- **Okio** — declined initially on the grounds that `FrameworkFreeCoreTest` is not the argument. It
  is not, but the *concentration* is: 16 `java.io.File` importers, and they are the download and
  cache-reconciliation subsystem where four tasks already have deleted-audio failure modes.
  `FakeFileSystem` is the Android justification (cu-218).
- **DataStore** — declined initially as too risky against the credential file. Adopted in three
  stages, with the hard requirement that `plex-session.sh` migrates alongside `ChronicleAuth.xml`,
  because that script *is* the device-verification tooling (cu-219).

Also worth recording: **`EncryptedSharedPreferences` is what upstream deprecated**, and this project
never used it — plain `SharedPreferences` only. So cu-219 is a modernisation, not a security fix, and
Tink is declined.

## Already answered by decision-24 (2026-09-07)

Three of the candidates below were settled as a side effect of the download-stack replacement, so
they need deciding here only in so far as this task records *why*:

- **Ktor** — **adopted**, and it is now the app's only HTTP stack. The reasoning is in decision-24;
  the short version is that OkHttp 5.0 dropped Kotlin Multiplatform support and Retrofit 3.0 never
  had it, so "keep them" was the choice that foreclosed portability, not the neutral one. The
  concern noted below — that Ktor "costs a second HTTP stack alongside OkHttp unless Retrofit moves
  too" — was correct, and the answer was to move Retrofit too (Ktorfit).
- **kotlinx-serialization vs Moshi** — **still open, deliberately.** `MoshiContentConverter` (~40
  lines) was written specifically so the transport migration did not also become a serializer
  migration: doing both at once would make a parsing regression and a transport regression
  indistinguishable. Moshi's KSP adapters still work. Note this is now the *only* thing keeping the
  Plex models JVM-bound, so it is the single change that would move the most model code — which is
  exactly what §2 already says.
- **Okio** — unaffected. Ktor uses `kotlinx-io`, which arrives transitively, so the `java.io.File`
  question in §5 stands on its own merits.

The portable-share figure in §5 is **stale** and should be re-measured before this task closes: the
HTTP layer is no longer JVM-bound, but the models still are.

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
- [ ] Anything adopted is checked against licence, [[decision-19]], and the **AGP 9.1.0** pin —
      not compileSdk, which is already 37
- [ ] Outcomes recorded as an ADR where a choice is architectural; the task file suffices for a
      list of declines
- [ ] For each candidate, the portability delta is recorded — but **no candidate is adopted on
      multiplatform grounds alone**; each must stand up on Android by itself
- [ ] `java.io.File` usage measured (15 files today) and the Okio question answered — noting that
      `FrameworkFreeCoreTest` bans framework imports, not JVM ones, so it is not itself the argument
- [ ] cu-182's 23.7% portable figure re-measured if anything here is adopted, so it inherits a
      current number
- [ ] No `commonMain` source set is created and no KMP plugin applied — cu-182 owns that decision
- [ ] Any adoption lands as its own task, not inside this one — this task decides, it does not
      implement
