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

### The lever, found by attempting DRAFT-175

DRAFT-175 said `SettingsViewModel`'s **15 constructor dependencies** made it untestable and that a
pure `SettingsPreferencesBuilder` had to be extracted first. **Checked before starting the
refactor, and the premise was wrong.** Nothing in the class calls the service locator, `init` only
registers a prefs listener, and it constructs fine from fifteen mocks.

The actual blocker was one line: `makePreferences` reads a string resource per row during
construction, so the test needs `@RunWith(RobolectricTestRunner::class)`. **One annotation, not a
737-line restructuring** — and restructuring that function with no tests to catch a mistake would
have been the wrong order anyway.

The result, from a single 12-test suite:

| | before | after |
|---|---:|---:|
| `SettingsViewModel.kt` | 0% | **47.6%** |
| `features/settings` | 19.00% | **43.14%** |
| overall | 41.59% | **43.45%** |

**That is the lever.** Nearly two points of overall coverage from one file, against ~1.1 points
from the five careful `data/model` suites before it. The pattern generalises: *construct the
ViewModel under Robolectric and exercise its public surface*, rather than hunting uncovered lines.

DRAFT-175 stays open but is **rescoped** — still worth doing for readability, no longer justified
as an unblocker.

### Where the weight actually is (measured 2026-09-05, after the first round)

Picking targets by convenience rather than by weight was costing progress. Ranked by share of all
43,125 missed instructions:

| package | missed | share | coverage |
|---|---:|---:|---:|
| `features/player` | 7,163 | **16.6%** | 37.8% |
| `features/currentlyplaying` | 4,183 | 9.7% | 27.5% |
| `data/sources/plex` | 3,681 | 8.5% | 55.1% |
| `features/bookdetails` | 3,393 | 7.9% | 27.7% |
| `data/local` | 2,854 | 6.6% | 65.5% |

The top three are **35%** of everything missing. Two lessons from working them:

- **Adapters and diff callbacks are Robolectric-reachable and were being written off.**
  `ChapterListAdapter` (621) and `CollectionsAdapter` (556) both sat at 0% and are ordinary Kotlin.
- **Workers genuinely are not.** `DownloadNotificationWorker` (1,420, the largest reachable-looking
  file) resolves `Injector.get()` in a *field initialiser*, so construction needs the whole DI
  graph. That is the documented cu-152 exemption, and it stands.

### What the remaining gap looks like

Of the top reachable targets left, the largest are `SettingsViewModel` (1,300 missed, 0% — blocked
by its 15 dependencies, hence DRAFT-175), `CurrentlyPlayingViewModel` (1,241 missed, 40%) and
`CachedFileManager` (993 missed, 27%). `MediaPlayerService` (1,956 missed) is a bound Service and
genuinely needs instrumentation.

---

## What stands between 45.7% and 75% (measured 2026-09-06)

Asked directly, and worth answering with arithmetic because the intuitive answer is wrong.

### 75% is roughly the ceiling, not a midpoint

| | instructions | |
|---|---:|---:|
| Total measured | 78,179 | |
| Covered today | 35,727 | **45.70%** |
| **Needs an emulator, or is debug-only** | 17,609 | **22.5%** |
| Reachable but not yet covered | 24,843 | |

Fragments, Activities, `Navigator`, bound Services, Workers and the debug mock server are 22.5% of
the codebase and cannot be executed by a JVM test. So **covering every reachable instruction in
the app yields 77.5%.**

Reaching 75% therefore means covering **92% of everything reachable that is not yet covered** —
not "more of the same", but very nearly all of it, including every error branch and every
defensive guard. That is the honest headline: 75% is not a stretch target on this configuration,
it is within 2.5 points of the maximum.

### The single biggest cause, and it is not what it looks like

Ranked by missed instructions, the largest category is "plain Kotlin" at 37.8%. Opening it up:

**65% of it is anonymous and inner classes**, and 82% of *those* are named inner classes of the
form `Owner$methodName$1`:

```
  172  CurrentlyPlayingViewModel$refreshTracks$1
  138  ChapterRepository$loadChapterData$2
  138  AudiobookDetailsViewModel$forceSyncBook$1
  173  LibrarySyncRepository$refreshLibrary$1
```

These are **coroutine bodies**. Every `viewModelScope.launch { … }` and every `suspend` function
compiles its body into a separate class, and JaCoCo counts it as its own uncovered unit. A test
that calls `forceSyncBook()` but does not drive the dispatcher to completion covers the *method*
and leaves the continuation at 0%.

So the main cause is not untestable architecture. It is that **a large share of the remaining
misses sits inside asynchronous bodies that only run when a test advances the scheduler far
enough and exercises every branch inside them** — including the `catch` arms, which need the
collaborator to be made to fail.

### What follows from that

- **The cheap wins are gone.** The work so far took whole classes from 0% by constructing them;
  what is left is branch-level coverage inside async code, which costs roughly one test per
  branch.
- **`advanceUntilIdle` is not enough on its own.** Covering a `launch` body's error arm means
  stubbing the collaborator to throw, per arm.
- **A realistic target remains 65-70%**, restated from the earlier section and now with a firmer
  basis: it implies covering roughly 75-80% of the reachable half, which is demanding but not
  absurd. 75% overall implies 92% and would mean writing tests whose only purpose is the metric.
- **The one structural lever left** is the fourth strategy noted above — moving pure logic into
  modules with no `android.*` imports, which shrinks the unreachable 22.5% rather than fighting
  it. That is an architectural decision, not a testing task.

---

## Should the pure-module extraction be done? (2026-09-06)

Three questions, answered with what this codebase shows rather than with general advice.

### 1. Did the session's work make changes and debugging easier?

Yes, and the evidence is that **the tests found four real defects while being written**, none of
which any existing test or the type system caught:

| defect | how it would have surfaced otherwise |
|---|---|
| `SORT_KEYS` advertised 4 keys with no comparator branch | a **crash on the library screen** after a settings import, unrecoverable through the UI |
| the sync-location row opened an **empty chooser** | a dialog with nothing in it on a device with no available volume |
| `mediaController.metadata` dereferenced unguarded (cu-23) | process death whenever Auto browsed without playing |
| the collapsed-sheet `isShown` guard (cu-141) | seven attempts, and it needed a *source-scanning* test to pin |

That is the honest measure of whether tests help: not the percentage, but whether writing them
surfaces things. It did, four times.

The structural work is separately defensible. `onCreateView` at 408 lines/CC 49 and
`makePreferences` at 748/CC 30 both exceeded what a reviewer can hold at once — which is what made
cu-141 cost seven attempts, since the guard and the listener sat inside a function nobody could
read end to end.

### 2. Is the pure-module extraction achievable?

**More achievable than expected, and the measurement is the argument.** `data/model` at 94.43%
already behaves like a pure module. Counting its Android dependencies:

- 13 of 20 files have **zero** `android.*`/`androidx.*` imports.
- Of the 7 that do, **most are Room annotations only** (`@Entity`, `@PrimaryKey`,
  `@TypeConverter`) — which a KMP-style module keeps, since Room supports it.
- **Only 8 imports in the whole package are genuine framework use**, and they cluster in three
  known places:
  - `Audiobook.toMediaItem` / `toAlbumMediaMetadata` — `MediaBrowserCompat`, `Bundle` (the Android
    Auto conversions, now tested under Robolectric)
  - `MediaItemTrack` — `Uri`, `MediaMetadataCompat`
  - `Chapter` — one `DateUtils` call

So the extraction is not "rewrite the model layer". It is **move three conversion functions into
the layer that consumes them**, which is where they arguably belong anyway: `toMediaItem` is a
presentation concern of the media-browser code, not a property of a book.

### 3. Is it a well-established pattern?

Yes — it is the standard Android architecture guidance (a `:domain` or `:core:model` module with
no framework dependencies), and it is the mechanism behind most "95% coverage" claims. It is not
novel or risky.

But two things temper it here:

- **The benefit is mostly the metric, not the code.** Those 13 files are *already* pure and already
  at 94%. A module boundary would enforce that they stay pure — real value — but would not make
  them more testable, because nothing is stopping them being tested today.
- **It is a one-way door on build structure**, and this is a single-module app by deliberate
  simplicity (D12 rule 6: any CI system should be a thin wrapper). Multi-module Gradle is more
  configuration for an agent to maintain.

### Correction (2026-09-06): it is not only `data/model`

The section above scoped the question to `data/model` because that was the package in hand. Asked
whether anything else benefits, and measured across the whole tree — **it is much bigger than one
package**:

**86 of 209 files (41%), ~8,000 lines, are already framework-free** (no `android.*`/`androidx.*`
beyond Room annotations). They are spread across most packages, and they are not trivia — they are
the decision logic:

| package | framework-free files | examples |
|---|---:|---|
| `data/model` | 17 | `BookSearch`, `SeriesIndexPatterns`, `Offsets`, `SourceId` |
| `features/player` | **10** | `SleepTimerState`, `ChapterSeekTarget`, `TrackListStateManager`, `CastEligibility` |
| `data/local` | 9 | all five repositories, `SettingsBackup` |
| `features/download` | 5 | `CacheReconciliation`, `ResumePlan`, `DownloadGroupId` |
| `util` | 5 | `DurationFormat`, `FlowCombinators` |
| `data/sources` | 4 | `IngestionPlan`, `SourceCapabilities` |

That this exists is not an accident. It is the result of years of deliberate extractions —
cu-21 pulled `SleepTimerLogic` out of the timer, cu-101 pulled `RefreshRate`/`BookCoverStyle` out of
settings, cu-136 made offsets value classes, cu-19 made the formatters pure. **The domain layer is
already there; it simply has no boundary around it.**

### The number that decides the question

| | coverage | instructions | missed |
|---|---:|---:|---:|
| **framework-free files** | **80.8%** | 17,835 | 3,432 |
| everything else | 35.7% | 59,585 | 38,296 |

The pure code is *already* at 80.8% with no module boundary at all. So a `:domain` module would
**not unlock testability** — the thing it is usually adopted for. Every one of those files is
testable today, and most are tested.

What a boundary would actually buy:

1. **It stops the drift.** Nothing currently prevents someone adding `import android.os.Bundle` to
   `SleepTimerState`. Today's purity is convention, enforced only by review — and this session
   found that review misses things.
2. **It makes the coverage number honest.** A `:domain` module reporting 80.8% and an `:app`
   module reporting 35.7% is a far more useful pair of signals than one blended 45.7%, which
   flatters the framework layer and hides the domain layer's real quality.
3. **It would speed the build**, since a pure module compiles and tests without the Android
   toolchain.

What it would not buy: any test that cannot be written today.

### Recommendation

**Do the small version now, and treat the module as a real option rather than a deferred one.**

Worth doing: move the three media conversions out of `data/model` into the player/browse layer.
That is a few hours, removes 8 of the package's framework imports, puts each function beside its
consumer, and needs no new Gradle module. It also makes the "`data/model` is pure" claim true
rather than nearly true.

On the `:domain` module, the earlier "not worth doing" was based on the wrong scope. With 86 files
and 8,000 lines in scope rather than one package, the case is genuinely arguable — but the reason
to do it is **enforcement and signal**, not testability, and that should be stated plainly rather
than sold as a coverage win. The cost is permanent multi-module build configuration in a project
whose D12 rule 6 says plain git and a shell script should be enough.

A cheaper 80% of the benefit: a **source-scanning guard test** in the existing style
(`ModelsWithoutDiTest`, `ScopedQueryTest`, `RepositoryDispatcherTest` all do this) that fails the
build when a file on a curated framework-free list grows an `android.*` import. That buys the
anti-drift enforcement for an afternoon and no build complexity. If the list proves stable and
useful, promoting it to a real module later is a smaller step, taken with evidence.

**And do not chase 75%.** Per the section above it implies covering 92% of everything reachable.
65-70% is the target that still means something.

---

## Testing the framework layer: the tools that exist and are not used (2026-09-06)

A `:domain` module does nothing for the **59,585 instructions at 35.7%** that make up the rest of
the app. Asked what covers *those*, and whether there is established guidance. There is, and most
of it is **already in this repo's dependency list, unused**.

### What the framework layer is made of

| kind | missed | share of all misses |
|---|---:|---:|
| **Fragments** | **9,000** | **21%** |
| Services | 2,975 | 7% |
| Workers | 2,212 | 5% |
| Activities | 1,359 | 3% |
| — | **15,546** | **37%** |

The single biggest untested body in the app is Fragments, and eight of them carry over 450
instructions each (`CurrentlyPlayingFragment` 1,356; `AudiobookDetailsFragment` 1,305;
`LibraryFragment` 1,259).

### The three standard tools

**1. `FragmentScenario` (`androidx.fragment:fragment-testing`) — the big one, and it is missing.**

The official way to test a Fragment in isolation: `launchFragmentInContainer<LibraryFragment>()`
drives the real lifecycle, and **under Robolectric it runs on the JVM** — no emulator. This is the
tool that reaches the 9,000 instructions above, and the only one of the three not already declared
in `libs.versions.toml`.

Caveat worth stating up front: these Fragments take their dependencies through
`ViewModelProvider.Factory` from the Dagger graph, so a scenario test needs a way to supply a test
factory. That plumbing is the real cost of this route, not the library.

**2. `TestListenableWorkerBuilder` (`androidx.work:work-testing`) — declared, wired, never used.**

`testImplementation(libs.work.testing)` is already in `app/build.gradle.kts`. The only mention of
`TestListenableWorkerBuilder` in the whole test tree is inside `WorkerDispatcherTest`'s *comment*.

It would reach the 2,212 Worker instructions — **except** that `DownloadNotificationWorker` and
`MoveSyncLocationWorker` both resolve `Injector.get()` in **field initialisers**, so construction
needs the whole DI graph regardless of the builder. Using this tool requires first giving the
workers a `WorkerFactory`, which cu-152 deliberately declined ("would buy nothing while no worker
is unit-tested"). That reasoning is now inverted: the tool is present, so the plumbing would buy
something.

**3. Robolectric — present, used in 40 files, and under-applied.**

Already the workhorse. This session used it to take `SettingsViewModel` 0% → 47.6%,
`ChapterListAdapter` 0% → covered, and `MediaServiceConnection` 0% → covered. Nothing stops it
being pointed at more.

### Honest assessment of the ceiling this changes

The earlier "77.5% ceiling" assumed Fragments, Services and Workers were unreachable. **With
`FragmentScenario` under Robolectric, and a `WorkerFactory` for the workers, most of that 37%
becomes reachable on the JVM.** The ceiling is not a property of Android; it is a property of
which tools this project has adopted.

That does not make 75% cheap — those tests are slower to write and slower to run than a pure unit
test, and Robolectric has real costs (it is why PIT cannot mutate through it, per the `pitestDebug`
allowlist). But "unreachable" was the wrong word, twice now.

### Recommended order

1. **`FragmentScenario` on one Fragment first** (DRAFT-179). Pick `CollectionsFragment` — 806
   instructions, the simplest of the eight, and its ViewModel is already tested so a failure is
   unambiguously the new plumbing. Prove the DI-factory approach on one screen before committing to
   eight.
2. **Then the workers** — a `WorkerFactory` plus `Configuration.Provider`, revisiting cu-152's
   exemption with the note that its premise has changed.
3. **The `:domain` module is orthogonal** and can happen whenever; it addresses enforcement and
   signal, not this.

---

## Autonomous run, 2026-09-06 — what shipped and what did not

Four tasks taken in the order the owner set: domain separation, then Fragments, then Workers.

### Done

| task | outcome |
|---|---|
| **cu-176** media conversions | `data/model` from 8 framework imports to **2**. `toMediaItem`, `toAlbumMediaMetadata` and `toMediaMetadata` moved beside their only callers in `features/player`; dead `MediaItemTrack.from` removed. |
| **cu-177** drift guard | `FrameworkFreeCoreTest` pins **87 files**, sabotage-verified twice (added import; missing file). Convention rule 6 in CLAUDE.md. |
| **cu-179** WorkerFactory | Both workers constructor-injected, `Configuration.Provider` installed, App Startup initialiser removed. `DownloadOutcomes.kt` extracted and tested. `features/download` **14.72% → 30.83%**. |

**Overall coverage 45.70% → 46.89%** this run; **40.47% → 46.89%** across the session.

### Not done, and why

**cu-178 (FragmentScenario) is back at `To Do`, half-solved.** Attempting it found *two* layers of
host coupling, not one:

1. **DI — solved and permanent.** `(activity as MainActivity).activityComponent!!` named a concrete
   Activity, so `EmptyFragmentActivity` failed in `onAttach`. `ActivityComponentHost` inverts it;
   `MainActivity` implements it; verified on device.
2. **AppCompat — not invertible.** Six of ten fragments call
   `(activity as AppCompatActivity).setSupportActionBar`. That is AppCompat's own API, so the host
   genuinely must be an `AppCompatActivity`, and `fragment-testing` 1.8.9 offers **no overload
   accepting a host class** — its four `launch`/`launchInContainer` signatures take only a fragment
   class, args, a theme and a factory.

The remaining route is a **debug-manifest `AppCompatActivity` host** plus `ActivityScenario`. That
ships an activity in the debug build, which is more visible than a proof of concept should decide
unattended. **The 9,000 Fragment instructions therefore remain untouched**, and they are still the
largest single body.

### Two things needing the owner

- **A download was never exercised** after the WorkManager change (cu-179). That is the risky half
  — cu-81, cu-85 and cu-153 are all downloads failing quietly — and it needs a real book fetched to
  a real volume.
- **cu-174, cu-175, cu-176, cu-179 are all `In Review`**, each because a screen changed or a
  criterion needs eyes.

---

## Would Hilt help? (2026-09-06)

Asked whether Hilt would make setup and testing easier. Measured against this codebase rather than
answered in general.

### What Hilt would genuinely delete

| | today |
|---|---:|
| Hand-written components + modules | **985 lines** across 7 files |
| `ViewModelProvider.Factory` inner classes | **360 lines** across 15 ViewModels |
| Field-injection boilerplate (`(activity as X).component.inject(this)`) | 14 sites |

`@HiltViewModel` + `by viewModels()` erases all 360 factory lines outright — every one is
mechanical, and each is a place a dependency can be forgotten. `@AndroidEntryPoint` erases the
`onAttach` injection call in ten Fragments. That is a real, uncontested simplification.

Hilt also brings `@TestInstallIn` / `@BindValue`, which is a genuinely better story than the
`testActivityComponent` seam cu-178 had to invent.

### What it would *not* fix — and this is the deciding fact

The blocker that stopped cu-178 is **not DI**. Six of ten Fragments call:

```kotlin
(activity as AppCompatActivity).setSupportActionBar(binding.toolbar)
```

`setSupportActionBar` is AppCompat's own API, so the host must really be an `AppCompatActivity`.
Hilt changes nothing about that: `launchFragmentInContainer` would still host the Fragment in
`EmptyFragmentActivity` and still fail in `onCreateView`. **The 9,000 Fragment instructions — the
largest untested body — stay untestable either way.**

Hilt would have made cu-178's *layer 1* unnecessary (`@AndroidEntryPoint` replaces
`ActivityComponentHost`). It does nothing for layer 2, which is the half that actually blocked.

### The other costs, stated plainly

- **It is a migration, not an addition.** Three components, three scopes, 149 `@Inject` sites, and
  a `WorkerFactory` that would become `@HiltWorker` + `HiltWorkerFactory`. Every screen touched at
  once, on a codebase whose verification is largely manual and device-bound.
- **KSP support for Hilt has historically lagged** — worth verifying against 2.57.2 before
  committing, since this project is deliberately KAPT-free (cu-8/cu-58) and reintroducing KAPT
  would be a real regression.
- **Dagger is not the thing hurting here.** Constructor injection is used wherever the app controls
  construction, and the locator is down to **6 real call sites** with a build guard preventing new
  ones. The DI is not the problem the review found.

### Recommendation

**Not now, and not for testability.**

The honest case for Hilt is the **360 lines of ViewModel factories** — that is mechanical
boilerplate with no design content, and deleting it is a real gain. But it is a large migration
justified by tidiness, competing against work that is currently blocked on something Hilt does not
touch.

If the goal is coverage, the ranked order is unchanged: **remove `setSupportActionBar` from the
Fragments** (or add the debug-manifest host), which unblocks 9,000 instructions and needs no new
framework. Revisit Hilt when a migration is wanted for its own sake — ideally after the Fragment
work, so it lands on code that has tests.

---

## Fragment frameworks: is there one that solves this? (2026-09-06)

Asked whether a popular framework — production or test side — would fix the Fragment blocker.

### The test side is already the right tool

`FragmentScenario` **is** the standard answer, and it works: cu-178 got it to `onAttach`
successfully. Nothing better exists, and nothing else is needed. The blocker is not the tool.

### The production side has a framework answer, and it is too big

The idiomatic solution to "Fragments coupled to their host's toolbar" is the **Navigation
Component** — `NavigationUI.setupWithNavController` owns toolbar and menu wiring per destination,
so no Fragment touches its Activity. It is the most widely adopted Android framework for exactly
this.

This project has **no Navigation Component**: `Navigator.kt` is hand-rolled and convention rule 9
routes everything through it. Adopting Navigation would mean nav graphs, `NavHostFragment`, and
rewriting every transition — a large change to fix a menu-routing detail.

### What the measurement says instead

The coupling is thinner than it looks:

- **`MainActivity` has no toolbar of its own** — no `supportActionBar` reference in it at all.
- **No fragment reads the action bar back.**
- **`MenuProvider` is already adopted in 4 of the 6** offending fragments, running *alongside*
  `setSupportActionBar`.

So `setSupportActionBar` does exactly one job: routing the fragment's own `Toolbar` menu through
the Activity's `MenuHost`. **A `Toolbar` can do that itself.** Six casts removed, no framework
added, and `AndroidX MenuProvider` — already in use — is the modern idiom for the menu half.

### Recommendation

**DRAFT-180: let the fragments own their toolbars.** It is the cheapest unblocker for the largest
untested body, it removes coupling rather than adding a layer, and it is a prerequisite for
Navigation if that is ever adopted — a Fragment that does not reach for its host is easier to move
under a nav graph, not harder.

Navigation Component remains a reasonable *future* choice on its own merits (type-safe args,
back-stack handling, deep links). It should not be adopted as a way to fix this.

---

## The Fragment blocker, removed (2026-09-06)

The owner chose toolbars-before-Navigation after the sequencing question below. That ordering
turned out to matter more than expected.

### What was actually in the way

**Two** host-type casts, not one, and only the first was a DI problem:

| layer | cast | fix |
|---|---|---|
| 1 | `(activity as MainActivity).activityComponent` | `ActivityComponentHost` (cu-178) |
| 2 | `(activity as AppCompatActivity).setSupportActionBar` | `setToolbarMenu` (cu-180) |

Layer 2 was the one that stopped everything, and **neither Hilt nor Navigation Component would
have fixed it** — `NavigationUI` still wires an *Activity-owned* toolbar. It needed removing, not
replacing.

It also turned out to be nearly vestigial: `MainActivity` has no toolbar of its own, no fragment
read the action bar back, and `Toolbar` implements `MenuHost` itself. Its only job was routing a
menu the fragment already owned.

### The result

| package | before | after |
|---|---:|---:|
| `features/home` | 18.44% | **82.47%** |
| `features/library` | 15.34% | **62.28%** |
| `features/collections` | 17.09% | **48.26%** |
| **overall** | 46.89% | **50.43%** |

Three screens, twelve tests. `CollectionsFragment` alone went 0% → 73%.

### The recipe, for the remaining five screens

1. Invert the injection: `check(injectFromHost { it.inject(this) })`.
2. Build the screen's **real** `ViewModelProvider.Factory` over fakes — not a mock, because
   `ViewModelProvider` picks among several `create` overloads and stubbing the wrong one fails at
   run time with "no answer found".
3. Install a mocked `ActivityComponent` whose `inject` populates the `lateinit`s.
4. `launchFragmentInContainer<T>(themeResId = R.style.AppTheme)`.
5. `SharedPreferences` must be a **fake, not a mock**, wherever the screen reads a `preferenceFlow`.

### A regression the device caught that no test would have

Moving the menu onto the toolbar produced **two search icons** on Home. The layouts already
declare `app:menu="@menu/…"`, and the providers inflated the same menu again — routing through the
Activity had masked the duplication. Fixed in all four menu-bearing fragments before committing.
**No unit test would have seen this**, which is why the device pass is not optional.

### Remaining

Five screens un-scenarioed: `AudiobookDetailsFragment` (1,305), `SettingsFragment` (469),
`SeriesIndexTesterFragment` (496), `ChooseUserFragment` (512), plus the two browse screens. The
pattern is proven; this is now repetition rather than investigation.

Then, per the owner's sequence: **Navigation Component**, then finish `Injector.get()`, then Hilt.
Navigation is now much safer to attempt — three of its destinations have scenario tests that would
catch a broken transition.

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
