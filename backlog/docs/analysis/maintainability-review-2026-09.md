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
