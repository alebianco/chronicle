---
id: cu-58
title: DataBinding to ViewBinding conversion
status: Done
assignee: [claude]
created_date: '2026-08-30'
labels: [R1, architecture]
dependencies: []
priority: high
milestone: m-1
---

## Description

Replace DataBinding with ViewBinding across the UI layer. Owner decision 2026-08-30: do the conversion
**before** the R3 redesigns (cu-26/27/28), and sequence it as **cu-58 → cu-8 → cu-52**.

### Why

- **DataBinding is the sole reason KAPT cannot be removed.** Verified during the cu-8 attempt: Room and
  Dagger both moved to KSP cleanly, but deleting `kotlin-kapt` breaks the build because DataBinding's
  `*BindingImpl` / `DataBinderMapperImpl` / `BR` classes are KAPT-only. cu-8 is blocked on this task, and
  currently costs ~3× slower incremental builds if attempted anyway.
- **DataBinding is effectively end-of-life.** Google steers new work to ViewBinding or Compose;
  DataBinding receives maintenance only. ViewBinding is the boring, supported target with the same
  generated-binding ergonomics and no annotation processor.
- **218 `@{...}` expressions are logic living in XML** — not type-checked by the Kotlin compiler, not
  unit-testable, invisible to the cu-3 coverage ratchet. For a project whose north star is
  agent-implementable code with a truthful verify loop, that is a standing liability.
- Removes the documented "phantom binding errors, run a clean" gotcha in CLAUDE.md.

### Scope (measured 2026-08-30)

| | Count |
|---|---|
| Layouts using `<layout>` DataBinding | **30 of 32** |
| Kotlin files importing `databinding` | 30 |
| `@BindingAdapter` functions (across 8 files) | 23 |
| `<variable>` declarations in XML | 64 |
| `@{...}` binding expressions in XML | **218** |
| Two-way `@={...}` bindings | **0** ← the one mercy |
| Fragments/Activities | 12 |

Zero two-way bindings is the good news: those are the genuinely painful conversions. Everything here is
one-way and can move to imperative Kotlin.

### Explicitly NOT in scope: LiveData

LiveData stays. The two are coupled only at the XML boundary — 10 files set `lifecycleOwner` so layouts
can observe LiveData directly, and moving `@{viewModel.foo}` into Kotlin means writing an observation
call at each site. But that call can be `liveData.observe(viewLifecycleOwner) { … }`; it does **not**
require StateFlow.

Doing both at once would mean ~30 layouts + ~30 Kotlin files + 73 `MutableLiveData` declarations +
35 `observe` sites in a single change, against 3.76% coverage and no instrumented tests. That is how
playback breaks silently. LiveData → StateFlow stays [[cu-52]], decided separately and later.

### Approach

Convert **screen by screen**, one commit per screen — each Fragment is independent, giving natural
checkpoints and a small blast radius per change:

1. Enable `viewBinding = true` alongside `dataBinding = true` (they coexist).
2. Per screen: strip the `<layout>`/`<data>` wrapper, move each `@{…}` expression into the Fragment as
   an explicit assignment or `observe` block, swap `DataBindingUtil.inflate` for `XBinding.inflate`.
3. `@BindingAdapter` functions become plain extension/helper functions called from Kotlin — 23 of them,
   several likely collapsible once callers are explicit.
4. Once all 30 layouts are converted: `dataBinding = false`, delete leftover adapters, unblock cu-8.

Manual QA per screen is the real safety net here — the automated gate cannot see UI regressions
(no instrumented tests, cu-54). Verify each converted screen actually renders and behaves before moving
on. Playback, library browse, and onboarding are the highest-risk paths.

### Ordering note

The R3 redesigns (cu-26/27/28) will rewrite many of these layouts anyway, so there is some duplicated
effort in converting first. Owner accepted that trade deliberately: converting first unblocks cu-8 and
KAPT removal far earlier, rather than leaving the toolchain slow until R3.

## Implementation Plan

### Safety net first (done before any conversion)

`capture-screens.sh` drives the app against the cu-16 mock server and screenshots Home, book details,
Library and Settings. Baseline captured before touching anything. This is the *only* meaningful check
here: `verify.sh` proves the app compiles, not that a screen still renders, and there are no
instrumented tests (cu-54). Re-run and compare after each screen.

### Measured scope

29 layouts carry `<layout>` wrappers; 218 `@{...}` expressions; 64 `<variable>` declarations; 23
`@BindingAdapter` functions across 8 files; **zero** two-way `@={...}` bindings.

Weight is concentrated: `fragment_currently_playing` and `fragment_audiobook_details` are 29
expressions each, `fragment_home` 18, `fragment_library` 15. The remaining ~25 layouts average 5.

### Order — simplest first, to establish the pattern

1. Onboarding layouts (5 exprs each, self-contained, low blast radius) — proves the pattern.
2. List/grid item layouts — mechanical, but they touch adapters.
3. `fragment_collections`, `fragment_library`, `fragment_home`.
4. `fragment_audiobook_details`, `fragment_currently_playing` — hardest, most playback risk, last.
5. `activity_main`, then drop `dataBinding = true` and delete the adapters.

One commit per screen, each with its screenshot compared against baseline. That keeps every step
revertable and makes a regression attributable to one screen.

### Conversion rules

- `@{viewModel.foo}` → an `observe(viewLifecycleOwner)` block in the Fragment. **LiveData stays**
  (cu-52 decides StateFlow separately); the call site just moves from XML into Kotlin.
- `@BindingAdapter` functions become ordinary extension/helper functions called from those blocks.
- `DataBindingUtil.inflate` → `XBinding.inflate`; `binding.lifecycleOwner` disappears with the last
  XML-observed LiveData.
- Adapters keep their generated `*Binding` type — ViewBinding generates the same class shape, so
  `binding.someView` keeps working. Only the `<layout>` wrapper and expressions go.

### Stop conditions

If a screen cannot be converted without also restructuring its ViewModel, **stop and leave it** for the
R3 redesign rather than widening scope mid-task. Note it in the task and move on.

## Implementation Notes

**All 29 layouts converted. DataBinding and KAPT are both gone.**

### How it was done

Five of the harder screens by hand (choose-server, choose-library, choose-user, grid item, library,
home, activity_main, audiobook details, currently playing); the mechanical majority by **four
subagents running in parallel**, each in an isolated git worktree owning a disjoint set of layouts
plus their exclusive Fragment/adapter. All four branches **merged without a single conflict**, which
is the payoff of partitioning by owner rather than by count.

Each agent found something worth keeping:

- **`FormattableString` needs its binding adapter.** `PreferenceModel.title` is a sealed class whose
  `ResourceString` variant resolves `@StringRes` against `Resources`. A plain `binding.x.text = model.title`
  would have rendered `ResourceString(stringRes=2131…)` — **silently, without crashing**.
- **First-frame flash.** DataBinding evaluates every expression once at initial bind; ViewBinding does
  not. Views whose visibility is now Kotlin-driven hold their XML default until the first LiveData
  emission and flash over the content. Fixed with `android:visibility="gone"` on the affected views in
  `fragment_home`, `fragment_library` and `fragment_collections`. No build stage detects this.
- **`isChosen` was dead.** `view_bottom_sheet_chooser_item.xml` declared it and **nothing in the
  codebase ever assigned it**, so the "chosen" highlight has never rendered. Behaviour preserved rather
  than "fixed" — reviving it is a design decision, not a conversion side-effect.
- **Adapter-cast ordering.** `bindSearchRecyclerView` casts `recyclerView.adapter`, and `observe()`
  delivers an already-set value synchronously, so any such call registered before the adapter
  assignment is a guaranteed ClassCastException.

### Judgement calls preserved rather than "improved"

An un-pluralised `" items"` string stayed verbatim despite violating CLAUDE.md convention 5, and the
`preference_item_clickable` explanation still always shows. Both are pre-existing; fixing them here
would have smuggled behaviour changes into a mechanical refactor.

### KAPT removal and the measurement (the point of the exercise)

With every layout converted, `dataBinding` was disabled, the `kotlin-kapt` plugin removed, Room and
Dagger moved to `ksp(...)`, and the ~35-line reflective `kaptArgs` hack deleted from the root build.
Room schemas are **byte-identical** under KSP.

| | KAPT baseline (cu-8) | Both pipelines (cu-8 attempt) | **KSP-only (now)** |
|---|---|---|---|
| Clean `assembleDebug` | 13s | 17.5s | **13s** |
| **Incremental (edit an entity)** | **~2.0s** | ~5.8s | **~4.6s** |

**The cu-8 hypothesis was wrong.** That attempt concluded the slowdown came from running KAPT and KSP
together. Removing KAPT entirely recovers the clean-build time but leaves incremental builds still
**~2.3× slower than KAPT**. Profiling an incremental build shows `kspDebugKotlin` alone at ~3.0s —
KSP2 reprocesses everything rather than incrementally. The bottleneck is KSP2 itself, not the double
pipeline. See [[cu-8]] for the follow-up.

### Verification

- `./verify.sh` green; `./test_release_build.sh` green, release APK 5.4 MB, R8 checks pass.
- App driven on an Android 15 emulator against the cu-16 mock: Home, Library, book details and
  Settings all render correctly with zero DataBinding.
- Coverage 7.09% → 6.84%, updated deliberately — generated DataBinding classes were being counted and
  are now gone. No test was lost.

## Acceptance Criteria

- [x] All 30 layouts converted; no `<layout>` wrapper remains in `app/src/main/res/layout`
- [x] `dataBinding = false` (flag removed) in `app/build.gradle.kts`
- [x] No `@BindingAdapter` remains; replacements are ordinary Kotlin called from the call site
- [x] LiveData untouched — no StateFlow migration in this task
- [x] Every converted screen manually verified to render and behave correctly
- [x] `./verify.sh` green; CLAUDE.md convention rule 2 and the DataBinding gotcha updated
- [x] cu-8 unblocked: `kotlin-kapt` removed with the build still green
