# Maintainability review — 2026-09-05

A structural quality pass over `app/src/main` (208 Kotlin files, 32,178 lines): layout
correctness, single ownership, single responsibility, cyclomatic complexity, and class/function
size. Measured, not impressionistic — every claim below has a number behind it.

**Headline:** the architecture is sound and the data layer is genuinely good. There is **one
systemic problem**, it is concentrated in the fragment layer, and it explains the coverage
distribution, the cu-141 bug class, and most of the remaining risk in this codebase.

---

## The one systemic finding: `onCreateView` is where everything hides

Cyclomatic complexity, worst offenders:

| CC | function | file |
|---:|---|---|
| **49** | `onCreateView` | `features/currentlyplaying/CurrentlyPlayingFragment.kt` |
| **30** | `makePreferences` | `features/settings/SettingsViewModel.kt` |
| **24** | `onCreateView` | `features/library/LibraryFragment.kt` |
| 21 | `playBook` | `features/player/AudiobookMediaSessionCallback.kt` |
| 20 | `onCreate` | `application/MainActivity.kt` |
| 17 | `onCreateView` | `features/bookdetails/AudiobookDetailsFragment.kt` |
| 15 | `onCreateView` | `features/collections/CollectionsFragment.kt` |

`CurrentlyPlayingFragment.onCreateView` runs from line 134 to line 542 — **408 lines in a single
function**, containing **six nested local functions** (`bookProgressText`,
`chapterPositionText`, `chapterRemainingText`, `renderPlayerText`, `renderPlayerArtwork`,
`refreshSlider`) plus 29 flow collectors and several listeners. The whole file is 543 lines, so
`onCreateView` **is** the class.

### Why it is written this way, and why that reason is real

`binding` is a **local `val` inside `onCreateView`** (10 of 14 fragments do this), captured by the
nested functions. That is not sloppiness — it is the pattern that avoids a nullable `_binding`
field and the `onDestroyView` nulling dance, and it is genuinely safer against the classic
"binding outlives its view" leak. **Do not "fix" it by reintroducing a nullable field.**

But it has a cost that is now measurable.

### The cost, in three independent forms

**1. It correlates almost perfectly with coverage.**

| package | fragment lines | coverage |
|---|---:|---:|
| `features/collections` | 427 | **0.00%** |
| `features/library` | 397 | **2.42%** |
| `features/home` | 268 | 18.44% |
| `features/settings` | 337 | 19.00% |
| `features/currentlyplaying` | 543 | 25.61% |
| `features/bookdetails` | 404 | 27.70% |

Against the data layer, which has no such structure:

| package | coverage |
|---|---:|
| `data/model` | **88.43%** |
| `data/sources` | **86.99%** |
| `features/search` | **83.50%** |
| `data/local` | 65.23% |

Logic inside a closure inside `onCreateView` **cannot be reached by a unit test at all**. It is
not that these packages are untested by neglect; they are untestable by construction. CLAUDE.md
already notes coverage "sits backwards" here — this is the mechanism.

**2. It is the cu-141 bug class.** That bug lived in exactly this structure: a guard
(`isShown`) and a listener nested inside `onCreateView`, both wrong, both invisible to every one
of 1389 unit tests, and it took **seven attempts** to find. The fix had to be pinned by a
*source-scanning* test (`CollapsedSheetGuardTest`) because no behavioural test can reach the code.

**3. It defeats review.** A 408-line function with CC 49 exceeds what can be held in working
memory — human or agent. CLAUDE.md principle 2 makes self-review mandatory; this structure makes
it unreliable.

### Recommended fix (incremental, low-risk)

Extract the *pure* helpers first — they need no `binding` at all and become directly testable:

- `bookProgressText`, `chapterPositionText`, `chapterRemainingText` take a `PlayerProgress` and a
  string resolver, return `String`. Move to a `PlayerText.kt` alongside `util/DurationFormat.kt`,
  which already proves the pattern works (pure over millis, tested without a `Context`).

Then extract the *renderers* as private methods taking `binding` as a parameter:

```kotlin
private fun renderPlayerText(binding: FragmentCurrentlyPlayingBinding) { … }
```

This keeps the local-`val` ownership exactly as it is — the binding is still passed down from
`onCreateView`, never stored — while collapsing the function and making each renderer reviewable
in isolation. Same for `LibraryFragment` (4 nested funs) and `CollectionsFragment` (1).

**Estimated effect:** `CurrentlyPlayingFragment.onCreateView` from CC 49 to roughly CC 12-15, and
three pure formatters become unit-testable, which is where `features/currentlyplaying` coverage
would move first.

---

## Second finding: `SettingsViewModel` has 15 constructor dependencies

Constructor dependency counts across all 15 ViewModels:

```
15  SettingsViewModel          ← outlier
11  CurrentlyPlayingViewModel  ← outlier
 9  AudiobookDetailsViewModel
 8  LibraryViewModel
 6  HomeViewModel
 5  CollectionsViewModel
 4  MainActivityViewModel
 3  SeriesIndexTesterViewModel, CollectionDetailsViewModel, ChooseUserViewModel
 2  LoginViewModel, FacetBooksViewModel
 0  ChooseServerViewModel, ChooseLibraryViewModel, BrowseViewModel
```

Two outliers, then a healthy tail. Fifteen dependencies is the clearest single-responsibility
violation in the codebase: `SettingsViewModel` owns preferences rendering, library management,
cache management, login/logout, settings import/export, sync-location moves and the licence
screen.

`makePreferences()` alone is **737 lines (CC 30)** (lines 228-965) building 36 `PreferenceModel` entries. It is
*declarative*, which mitigates the complexity considerably — this is a UI description, not
branching logic, and it reads linearly. But it is rebuilt wholesale on **every** preference change
via `OnSharedPreferenceChangeListener`.

**Recommendation — split by responsibility, not by line count:**

- `SettingsPreferencesBuilder` — takes `PrefsRepo` + a string resolver, returns
  `List<PreferenceModel>`. Pure, and immediately unit-testable, which `formatRefreshRate` and
  `formatBookCoverStyle` already demonstrate is the right seam (both extracted for cu-101 for
  exactly this reason).
- Settings **actions** (export/import, cache, sync-location) stay in the ViewModel.

This is a clean cut: the builder needs 2 dependencies, not 15.

**Note the good precedent already in place.** cu-101 pulled `refreshRateLabel` and
`BookCoverStyle` decisions out into pure, tested files and left only the `Context` string lookup
behind. That is the same move, applied to the whole builder.

---

## What is genuinely good (do not disturb)

These are strengths worth stating, because a review that only lists problems misleads.

- **Layouts are clean.** Max nesting depth **5**, largest layout 385 lines. No deep hierarchies,
  no `RelativeLayout` nests. `values-land` carries exactly two overrides. After cu-141 the
  artwork visibility integer applies **only** to the artwork itself.
- **Single ownership of data is well enforced.** The `MutableStateFlow` private / `StateFlow`
  public pattern is used consistently (13 pairs in the largest ViewModel, no leaks of the mutable
  type). All 78 DAO call sites live in four repositories — nothing in `features/`, `application/`
  or the player touches a DAO. `ScopedQueryTest` enforces it at build time.
- **Structural rules are enforced by tests, not convention.** Twelve source-scanning guards
  (`RepositoryDispatcherTest`, `ScopedQueryTest`, `PostValueUsageTest`, `ServiceLocatorUsageTest`,
  `FirstFrameFlashTest`, `TokenLoggingTest`, `CollectionLoggingTest`, `TouchTargetSizeTest`,
  `ContentDescriptionTest`, `WorkerDispatcherTest`, `ModelsWithoutDiTest`, `ViewModelFactoryTest`)
  plus the new `CollapsedSheetGuardTest`. This is unusually disciplined and is the main reason the
  codebase has not drifted.
- **Only 13 `TODO`s**, all in the documented `MediaSource` seam (`LocalMediaSource`,
  `PlexMediaSource`), which CLAUDE.md already flags as scaffolding awaiting cu-33.1. No `FIXME`,
  no `HACK`.
- **The data layer is exemplary.** `data/model` at 88% coverage with value classes
  (`SourceId`, `BookOffset`/`TrackOffset`/`TrackIndex`) making whole bug classes uncompilable.

---

## Second scope: tests, scripts and hooks (added 2026-09-05)

The first pass covered only `app/src/main`. Test code, build scripts and git hooks rot the same
way and are maintained by the same people, so they were measured too.

**Verdict: clean.** This scope produced no code findings — only a housekeeping one.

### Test suite — 203 files, 29,271 lines (a 0.91:1 ratio against production)

| signal | result |
|---|---|
| Tests with **zero assertions** (vacuous pass risk) | **0** |
| `@Ignore`d / disabled tests | **0** |
| `Thread.sleep` (flake risk) | **0** |
| Hardcoded absolute paths | **0** (one match was the Plex route `/home/users`) |
| Largest test file | 950 lines (`RoomSchemaTest`), only one over 500 |
| Duplicated fake/stub classes | **0** — nine fakes, each declared once |
| `runTest` vs `runBlocking` | 40 files vs 4, and all four bridge a suspend call inside a
non-suspend assertion rather than waiting on timing |
| Mocking libraries | **one** (MockK), in 41 of 200 files; the rest use real fakes |

A shared `chronicle/testing/` package (`FakePlexServer`, `MultiTrackBook`, `TestSources`,
`PlexFixtureContractTest`) is used by **54 files**. That is the thing most test suites lack and
the reason there is no fixture duplication here.

Notably the test tree does **not** have the production tree's problem: no 400-line functions, no
nested-closure structure, and file sizes cluster tightly.

### Scripts — 860 lines across six files

Five of six use `set -euo pipefail`. The exception is `test_release_build.sh`, which is also the
only script on `#!/bin/zsh` and uses bare `set -e` — no `-u`, no `pipefail`. It contains 13 pipes,
including the load-bearing R8 assertion at line 52 that greps the dex for class descriptors.

**This is not a live defect**: that specific pipeline is guarded by an explicit
`[[ ! -s ${DESCRIPTORS} ]]` emptiness check immediately after, so a failing `dexdump` is caught by
content rather than by exit status. Deliberate, not accidental. Adding `pipefail` would still be
cheap insurance if the file is touched for another reason — not worth a task on its own.

Minor: the debug package id is repeated as a literal in three scripts with no shared helper.
CLAUDE.md already documents the `.debug` suffix trap prominently, so this is known-and-flagged
duplication rather than a hidden one.

### Git hook

`pre-commit` runs `./gradlew ktlintCheck` and branches on `$?` correctly. It **is** version
controlled — tracked at the repo root and byte-identical to the installed
`.git/hooks/pre-commit`.

### The one real finding: `.worktree/` is 4.1 GB

`git worktree list` reports **12** registered worktrees; `.worktree/` holds **41** directories.
So 29 are orphaned leftovers from completed tasks (`task-cu-22-bookmarks`, `task-25-fuzzy-search`,
`task-52-stateflow`, …), and all 12 registered ones belong to tasks that are `Done` or
`In Review` with their work merged.

Checked before recommending anything — **nothing would be lost**:

- 11 of 12 have no uncommitted changes and no unmerged commits.
- `task-33-interface-carve` has 2 modified files: stale `coverage-baseline*.txt`, superseded.
- `task-141-landscape-progress` shows "2 unmerged commits", but the branch is **339 files behind**
  `feature/agentic-dev` — the commits are parked WIP on a stale base, superseded by `a9ba05a`.
  Its diff is mostly *deletions* of files that exist now.

`git worktree prune` clears the orphan registrations; the 29 empty directories and the 12 merged
worktrees are then safe to remove. **Left for the owner to run** — reclaiming 4.1 GB by deleting
directories is not something to do unattended.

---

## Coverage: what the ceiling actually is

### A correction to the first version of this section

The first draft claimed a hard **72.4% ceiling** and called 95% "arithmetically impossible on this
platform." That overstated the case by treating a property of *this build configuration* as a
property of Android. Three specific mechanisms move the boundary, and **two are already partly in
use in this repo**:

**1. Exclusion rules.** `app/build.gradle.kts` already excludes Dagger factories, Room `_Impl`,
DataBinding and `R` classes — but **not Moshi's generated `*JsonAdapter` classes**, which are
**7,882 instructions, 9.2% of the measured codebase**. That is generated code nobody writes or
reviews, sitting in the denominator and dragging the number down. Excluding it is a config change,
not a testing effort.

**2. Robolectric.** The first draft called Fragments and adapters "unreachable by a JVM unit test."
That is wrong here: **Robolectric is already used in 40 test files**, including
`GroupedSearchAdapterTest`, `SpeedChooserLayoutTest`, `ExpandedBottomSheetTest` and
`BookmarkListAdapterTest`. Adapters, ViewHolders and bottom sheets — **4,544 instructions, 5.3%**
— are therefore JVM-reachable *today*, not blocked. Robolectric carries real cost (it is slow, and
PIT cannot mutate through it — see the `pitestDebug` allowlist), so covering 100% of UI through it
is not the goal; but "unreachable" was the wrong word.

**3. Instrumented tests.** The suite exists (cu-54, two managed devices) and `jacocoTestReport`'s
`executionData` already globs `**/*.ec`. Counting them makes the ceiling ~100% by definition.

### The revised numbers

| scope | instructions | ceiling |
|---|---:|---:|
| Measured today | 85,963 | 72.4% |
| **After excluding Moshi generated code** | 78,081 | — |
| Of that, Fragments + Activities | 9,193 | **11.8%** |
| **Unit + Robolectric ceiling** | | **≈88%** |
| With instrumented runs counted | | ≈100% |

So **≈88% is reachable without a single instrumented test**, once generated code is excluded and
Robolectric is used as widely as it already is in places. Only Fragments and Activities — 10.7% of
today's measurement — genuinely need an emulator.

### What that means for a target

95% remains a stretch for a monolithic `:app` module, and the fourth strategy that makes it routine
elsewhere — **pure domain modules with zero `android.*` imports** — is a real architectural option
this project has not taken. It is worth noting that `data/model` already behaves like one (88.43%
coverage, zero Android imports in its core files); the difference is that it lives inside `:app`
rather than in its own module.

A revised, defensible target: **65–70% overall** after the Moshi exclusion, rising as the
`onCreateView` extractions (DRAFT-173/174) move logic out of the instrumentation-bound 10.7%.
That is a real target rather than a permanently-failing one, and it no longer rests on a ceiling
figure that was an artifact of the build config.

### Applied — and it moved the number the *other* way

The Moshi exclusion was applied rather than filed, and the result is worth recording because it
contradicts the reasoning that motivated it:

```
before: 85,963 instructions, 40.68% covered
after : 78,065 instructions, 40.47% covered
```

**Coverage went down.** The generated adapters were **51.7% covered** — better than the 40.7%
codebase average — because the `*-real-shape.json` fixture tests (cu-24) genuinely parse through
them. Removing them removed proportionally more *covered* instructions than missed ones.

The exclusion is kept anyway, because the metric should measure code someone wrote and can fix; a
generated `fromJson` body being well covered is a side effect of testing the models, not a signal
about the codebase. But the general lesson stands against the assumption that produced it:
**excluding generated code raises the number only when that code is worse-covered than average**,
and here it was better. Anyone reaching for exclusions to improve a percentage should measure the
excluded set's own coverage first.

---

## Coverage work done (2026-09-05)

Acted on rather than filed. Chosen by reading JaCoCo's per-line data, never by chasing the
percentage — two candidates were **rejected** on inspection for that reason.

### data/model: 88.43% → 94.43%

| suite | what it pins |
|---|---|
| `AsServerModelTest` | `accessToken = this.accessToken ?: ""` — the cu-33 empty-token root |
| `MergeSeriesFieldsTest` | narrator/series/seriesIndex through **both** merge arms |
| `TrackListEdgeCaseTest` | the empty-and-absent branches on the track-list helpers |
| `AudiobookMediaItemTest` | `toMediaItem`/`toAlbumMediaMetadata` under Robolectric (Auto) |
| `CollectionSortAndConverterTest` | the unofficial sort-code fallback, the converter's empty branch |

### Elsewhere

| package | before | after |
|---|---:|---:|
| `features/library` | 2.42% | **15.34%** |
| `features/login` | 19.49% | **29.63%** |
| `features/collections` | 0.00% | **8.69%** |
| **overall** | 40.47% | **41.59%** |

### Two targets deliberately rejected

Worth recording, because both look attractive in a coverage report and neither is real work:

- **`MediaMetadataCompatExtKt`** — 738 missed instructions at 6.9%, the largest single non-Fragment
  gap. Every member is an `inline val`, so its body is compiled into the *caller* and JaCoCo cannot
  attribute execution back to the declaring file. Covering it would not move the number, and the
  file is upstream boilerplate.
- **`ChapterAssembly.kt`** — reported at 36.2% with `assembleChapters` showing 0%, while
  `AssembleChaptersTest` exercises it thoroughly. Same `inline` artifact.

**79 of the 353 instructions still missing from `data/model` are this artifact plus data-class
`equals`/`hashCode`.** Another 112 are Android media builders. Chasing either would be gaming the
metric.

### What the remaining gap looks like

Of the top reachable targets left, the largest are `SettingsViewModel` (1,300 missed, 0% — blocked
by its 15 dependencies, hence DRAFT-175), `CurrentlyPlayingViewModel` (1,241 missed, 40%) and
`CachedFileManager` (993 missed, 27%). `MediaPlayerService` (1,956 missed) is a bound Service and
genuinely needs instrumentation.

---

## Fakes versus mocks: what this repo already does

Asked whether the "prefer fakes over mocks" advice applies here. **It does, the repo already
follows it in the places that matter, and there is one concrete gap.**

### The current split, measured

| approach | reach |
|---|---|
| MockK | **43** of 200 test files |
| Hand-written fakes | 10 classes, each declared once, no duplicates |
| **Real Room databases** (in-memory) | **9** test files |

The nine using a real `Room.inMemoryDatabaseBuilder` are the strongest form of the advice — a real
implementation against a test-only backing store, not a stand-in. `RoomSchemaTest` goes further
still and opens a **real file** at an old schema, because an in-memory database is created fresh at
the current version and never migrated: a migration bug is invisible to the in-memory variant.

The shared fakes (`FakePlexServer`, `FakePlexPrefsRepo`, `FakeProgressApi`, `FakeBookmarkRepository`,
`TestDispatcherProvider`) live in `chronicle/testing/` and are used by 54 files.

### Where mocks are the right call, and why

Two cases in this codebase genuinely warrant MockK, both already documented in the tests
themselves:

- **Final classes with large surfaces.** `AudiobookDetailsViewModelTest` mocks
  `MediaServiceConnection` and `PlexConfig` and says why: MockK handles final classes on the JVM,
  and extracting interfaces across 653 lines to avoid it "would risk far more than it buys."
- **Verifying that something did *not* happen.** `coVerify(exactly = 0) { … }` is the natural way
  to assert that picking a password-protected user sends no request. A fake would need to grow a
  call log to answer the same question.

### The gap: a `relaxed` mock can make a test pass vacuously

This is the real risk, and it bit twice while writing tests for this review.

`CollectionsViewModel` reads its state through `SharedPreferences.booleanFlow`/`stringFlow`, which
`callbackFlow` builds by **registering a listener** and emitting on callback. A
`mockk<SharedPreferences>(relaxed = true)` returns `false`/`null` from the getters and silently
drops the registration — so the flow never emits, `combine` never fires, and **every assertion
about the resulting list passes against a flow that produced nothing.** The test would be green and
would prove nothing.

`CollectionsViewModelTest` therefore uses a hand-written `FakePrefs` with a real listener list. The
rule worth generalising:

> **Mock a collaborator you only call. Fake a collaborator that calls you back.**

Anything callback-, listener- or flow-shaped needs a fake, because a relaxed mock's silence is
indistinguishable from correct behaviour. That covers `SharedPreferences`, `Fetch2` listeners, and
the `MediaControllerCompat.Callback` surface.

### What this does *not* explain

`data/model` sits at **88.43%** while using MockK in only 1 of 35 test files, and it is tempting to
read that as fakes causing high coverage. **That inference is backwards.** `data/model` is pure
logic — `BookSearch.kt`, `SeriesIndexDiagnostics.kt`, `LoadingStatus.kt` have *zero* Android
imports — so it needs no test doubles at all. Purity is the cause; the low mock count is a
symptom. Introducing fakes into `features/currentlyplaying` would not move it to 88%; extracting
its logic out of `onCreateView` (DRAFT-173/174) would.

### Verdict

No action needed on the mock/fake balance itself — it is already well judged, and the ratio is not
a problem to fix. The one thing worth adding is the rule above as a written convention, so the
`relaxed`-mock trap is not rediscovered a third time.

---

## Suggested follow-ups

Filed as drafts for owner triage rather than actioned here, since each changes structure across
several files and none is a correctness fix.

| draft | scope | value |
|---|---|---|
| DRAFT-173 | Extract the three pure text formatters out of `CurrentlyPlayingFragment.onCreateView` | Highest value/risk ratio: pure functions, immediately testable, no ownership change |
| DRAFT-174 | Extract renderers as `private fun …(binding)` in the three worst fragments | Collapses CC 49 → ~12; makes review tractable |
| DRAFT-175 | Split `makePreferences` into a pure `SettingsPreferencesBuilder` | Takes `SettingsViewModel` from 15 deps toward 8-10; 790-line function becomes testable |

**Deliberately not recommended:**

- Reintroducing a nullable `_binding` field. The local-`val` capture is the safer pattern; the
  problem is function length, not ownership.
- A blanket "max function length" ktlint rule. It would fire on `makePreferences`, which is
  declarative and legitimately long, and on Dagger modules. The two named extractions are
  targeted; a blanket rule would generate noise and get suppressed.
- Splitting `MediaPlayerService` (1048 lines). It is long but cohesive — one Android component
  with a mandated lifecycle — and it sits at 37.75% coverage, the best of any `features/` package.
