---
id: cu-52
title: StateFlow migration (LiveData replacement)
status: In Review
assignee: []
created_date: '2026-07-13'
labels: [R2, architecture, trust]
dependencies: []
priority: high
milestone: m-2
---

## Blocker cleared (2026-08-31)

cu-58 is Done, so this is unblocked — but it still needs an owner decision on *whether* to migrate at all (see below).

## Description

M2: migrate ViewModels/repositories from LiveData to StateFlow. Upstream's own todo flags this as uncertain ('may not be worth it if LiveData works well'). Depends on dispatcher injection (cu-15). CLAUDE.md currently mandates LiveData — this draft is the trigger to revisit that convention, not a committed task.

Analysis: [`M2-stateflow-migration-plan.md`](../docs/analysis/M2-stateflow-migration-plan.md).

## Sequencing (owner decision 2026-08-30)

Third in the UI-layer sequence: **cu-58 (DataBinding→ViewBinding) → cu-8 (KAPT→KSP) → this**.

Deliberately kept out of cu-58. The two are coupled only at the XML boundary: 10 files set
`lifecycleOwner` so layouts can observe LiveData. Once cu-58 moves observation into Kotlin, those call
sites are `liveData.observe(viewLifecycleOwner) { … }` — which works fine and does **not** require
StateFlow. Bundling them would have meant ~30 layouts + 73 `MutableLiveData` declarations + 35
`observe` sites in one change against 3.76% coverage.

Scope when it starts: 28 files reference LiveData, 73 `MutableLiveData` declarations, 35 `observe`
call sites.

## Owner decision (2026-09-01): migrate, promoted to R2

The open question below is **answered: yes**. Promoted from R4 draft to an R2 task on *correctness*
grounds rather than tidiness — the argument changed after the cu-73 device session.

Three of the fifteen device-only bugs were async-write races, and the mini-player one was fixed by
turning seven `postValue` calls into `value =`. **72 `postValue` sites remain.** `postValue` is
asynchronous and coalesces, so a read-after-write sees a stale value and two posts in the same tick
collapse to one — the shape of every one of those three bugs. The worst remaining form is
read-modify-write, e.g. `SettingsViewModel.kt:106` doing `postValue(it.copy(...))`.

StateFlow makes that class of bug structurally impossible: `value` is synchronous, and
`MutableStateFlow.update` is atomic. That is why this is worth doing now, and why it is *not* a
cosmetic migration.

Sequencing note: the prerequisites named below are both Done (cu-58, cu-8), so this is unblocked.
Do it **after** cu-33 if the two collide, since cu-33 changes the same repository seams.

### Original open question, kept for the record

Unlike cu-58, nothing is blocked by LiveData and it is not deprecated. Upstream's own note ("may not
be worth it if LiveData works well") stands on its own terms — the postValue race record is what
overrides it.

## Implementation Notes (2026-09-04)

**The owner chose the full migration over the proposed split.** The plan below argued for doing the
`postValue` correctness half alone; that was declined, and the whole framework migration was done in
one branch. The plan is kept underneath for the record, because its *measurements* were right even
though its recommendation was not taken.

### What changed

`LiveData` is gone from the app. Every state holder is a `MutableStateFlow`, every DAO returns
`Flow`, and `postValue` does not appear in any of the three source sets. The `util/` combinators it
was built on (`DoubleLiveData` and friends, the three `PreferenceLiveData` classes, `observeOnce`)
are deleted — nothing referenced them any more.

The Room fork named in the plan was resolved by **changing the DAO return types**, not by
`.asFlow()` bridges. Fifteen DAO methods return `Flow` now, so the app never ran both frameworks and
convention 3's "don't mix ad hoc" was never violated mid-flight — the tree simply stayed red between
the data layer and the last fragment.

New shared machinery, all in `util/`:

- `FlowCombinators.kt` — `combineDistinct` (2/3/4-arity) and `combineDistinctAsync`, replacing the
  hand-rolled `MediatorLiveData` subclasses. The `distinctUntilChanged` is mandatory rather than
  optional: it *is* the cu-110 fix. `STOP_TIMEOUT_MILLIS` lives here so every screen makes the same
  `WhileSubscribed` trade.
- `FlowCollect.kt` — `collectWhileStarted` / `collectEventsWhileStarted`, wrapping
  `repeatOnLifecycle(STARTED)`. A helper rather than 121 hand-written blocks because the plausible
  wrong forms are subtly broken: `launchWhenStarted` buffers instead of cancelling, and a bare
  `launch` never stops.
- `PreferenceFlow.kt` — `booleanFlow`/`stringFlow`/`floatFlow` over `callbackFlow`.
- `FlowTestExt.kt` (test) — `keepCollected`, `settledValue`, `settledValues`.

### Three bugs this found, none of which a unit test had caught

1. **A latent precedence bug** in `AudiobookDetailsViewModel.isBookInViewPlaying`:
   `isBookActive ?: false && currState?.isPlaying ?: false` parses as `isBookActive ?: (false && …)`,
   so a non-null `isBookActive` short-circuited and the playback state was never consulted. Non-null
   `Flow` sources make the intended expression the only one that compiles.
2. **`PlexLoginRepo.loginEvent` had no value until its first post**, so `loginEvent.value` was null
   for anything reading in the same main-loop pass as construction — `MediaPlayerService` and
   `MainActivity` both do `.value?.let`, which silently did nothing in that window.
3. **The library screen rendered "No books found" over a full library** — found on the tablet, fixed,
   and now guarded by `CollectorCachesItsValueTest`. See below; this is the one that matters.

### The one that got through, and the guard for it

`LibraryFragment` caches several sources in locals and combines them in one `refreshEmptyStates()`.
The converted collectors discarded their emission — `collectWhileStarted(viewModel.books) {
refreshEmptyStates() }` — so `latestBooks` stayed `emptyList()` for the life of the screen. It
compiles, all 1301 tests passed, and Home was unaffected because it reads `.value` rather than
caching.

`CollectorCachesItsValueTest` fails the build on a `latest*` local that no collector assigns. It is a
*source* guard for the same reason `FirstFrameFlashTest` is one: nothing that inspects a laid-out
view can tell "the flow has not emitted" from "the emission was thrown away", and the ViewModel is
correct in both cases. Sabotage-verified by reintroducing the exact line.

### Two things worth knowing before touching this again

- **`stateIn`'s sharing policy is a real choice, not boilerplate.** `AudiobookDetailsViewModel.audiobook`
  must be `Eagerly`: five click handlers read `.value` *without* collecting, and under
  `WhileSubscribed` the offline guard read the `null` seed and let an uncached book reach the player
  with no server. A test pins it, sabotage-verified.
- **Testing a `WhileSubscribed` flow needs a subscriber *and* a drained dispatcher**, and
  subscribing to two of them one at a time does not work — `advanceUntilIdle` lets the first settle,
  and the second one's `distinctUntilChanged` then suppresses its own emission, leaving it on the
  seed. `settledValues` subscribes together for that reason. This cost several wrong diagnoses.

### Coverage

Aggregate 38.73% → **39.52%**, every package up, no baseline lowered. `util` initially *fell*
(45.11% → 30.46%) because ~200 lines of dead combinator stayed behind — the per-package ratchet
caught exactly what it exists to catch, and deleting the dead code was the honest fix rather than
lowering the baseline. It reads 52.13% now.

### Device verification (tablet, real household server, 196 books)

All four tabs, playback, and a background/restore cycle. Playback ran across a chapter boundary with
the slider, "Ch 40 of 107", the chapter title and "7h 5m left in book 37%" all staying consistent;
backgrounding and restoring re-subscribed every collector and caught up correctly, with the player
sheet and the mini player both showing live state. Frame counts are **0 rendered while paused** and
~73 per 20 s while playing, identical whether the player sheet is open or collapsed — so the app
draws nothing when idle and the remaining jank figure is the 1 Hz tick measured against a 60 fps
budget, matching cu-140's recorded numbers. Unchanged by this work.

### Follow-ups

None. Nothing was deferred and no part of the scope was left out.

---

## Original plan (2026-09-04), kept for the record

## Implementation Plan (2026-09-04)

### The recorded scope is understated — measured, not read

| | task says | actual |
|---|---|---|
| files referencing LiveData | 28 | **39** |
| `MutableLiveData` declarations | 73 | **83** |
| `observe` call sites | 35 | **121** |
| `postValue` sites | 72 | **68** |

`observe` is 3.5× the recorded figure. That matters because it is the number that decides how big a
"phased rollout" actually is.

### The blocker the task does not mention: Room returns LiveData

Nine DAO methods across `BookDatabase` and `TrackDatabase` return `LiveData<…>` **directly from
Room**, and eight files build on `DoubleLiveData`/`TripleLiveData`/`QuadLiveDataAsync` combinators
over them. So "migrate ViewModels to StateFlow" is not a ViewModel-local change: either the DAOs
change return type (Room supports `Flow`, so this is possible but touches the data layer and every
combinator), or each ViewModel calls `.asFlow()` and the app runs **both** frameworks — which
CLAUDE.md convention 3 explicitly forbids ("don't mix ad hoc").

That is a genuine architectural fork, and it is the reason this task is much larger than its
description implies.

### Recommendation: split, and do the correctness half first

**The justification for promoting this to R2 was correctness, not tidiness** — three cu-73 device
bugs were `postValue` races. That half can be fixed *without* migrating anything:

- `postValue` is asynchronous and coalescing; `value =` is synchronous. Every one of those three
  bugs was fixed by that substitution, not by StateFlow.
- The one **read-modify-write** the task names (`SettingsViewModel.kt:124`,
  `postValue(it.copy(shouldShow = …))`) is a genuine lost-update race, and `LiveData` has no atomic
  `update {}`. That single site is the strongest argument for StateFlow — and it is *one site*.

So the plan is:

1. **cu-52a (this task): eliminate the `postValue` race class.** Convert the 68 sites to `value =`
   where the call is already on the main thread, and to an explicit main-dispatcher hop where it is
   not. Fix the read-modify-write. Add a guard test in the style of `InternalApiUsageTest`, which is
   criterion 4 and is what stops the sites creeping back. This delivers the entire correctness
   argument at a fraction of the risk.
2. **cu-52b (new task): the framework migration itself**, starting with the Room return types, since
   that is the real dependency and the thing that decides whether the rest is even coherent.

Splitting is proposed rather than assumed — see "What needs your eye" at the end. The pilot
criterion (`features/home`) is preserved either way: it is the smallest file with real `postValue`
sites and is where step 1 starts.


## Acceptance Criteria

- [x] Decision recorded: migrate (owner, 2026-09-01)
- [x] Pilot one feature end to end before any rollout — `features/home` was the pilot, in
      `61193ae`, before the rest followed
- [x] No `postValue` remains **anywhere**; `PostValueUsageTest` is a blanket ban across all three
      source sets, not the allowlist it started as
- [x] A guard test fails the build on a new `postValue`, in the style of `InternalApiUsageTest` —
      sabotage-verified by reintroducing one
- [x] CLAUDE.md convention 3 updated to match: it now mandates `StateFlow`, documents the collect
      helpers, the `stateIn` sharing choice, and what a test needs before reading a flow
- [x] Phased rollout completed — every `LiveData` is gone from the app, not just the recorded 28
      files (the measured figures were 39 files, 83 declarations, 121 `observe` sites)

**Retired rather than met:** *"a read-modify-write uses `update {}`"*. The criterion named
`SettingsViewModel`'s `postValue(it.copy(shouldShow = …))` as a lost-update race and called it the
strongest single argument for StateFlow. It is now `_bottomChooserState.value =
_bottomChooserState.value.copy(…)`, a plain assignment, because **every writer of that field is a
click handler on the main thread** — there is no concurrent writer to lose an update to, and
`update {}` would imply one exists. The race the criterion was written against was the *deferral*
(`postValue` reading a stale `.value` a pass later), and that is what the conversion removed.
`update {}` remains the right tool the moment a second writer appears off the main thread.
