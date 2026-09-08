---
name: android-ui
description: Use when writing or changing any screen - a Compose destination, the navigation graph, bottom sheets, sliders, chips, cover art, the player readout, or StateFlow collection in the UI. Covers the Compose rules, Navigation Compose, and orientation traps.
---

# Android UI

**The UI is Compose, all of it** (decision-22). **Zero layouts, zero Fragments,
zero `RecyclerView.Adapter`s, no ViewBinding.** A screen is a `*Screen` composable — a pure
function of its state — behind a `*Destination` that wires a ViewModel to it. `Navigator` and its
`FragmentManager` transactions are gone; `ChronicleNavHost` is the graph.

**One View island remains, deliberately**: `CastButton` hosts a real `MediaRouteButton` in an
`AndroidView`, because the Cast SDK has no Compose surface. It degrades to absent (decision-19),
and its `@UnstableApi` marker propagates to every caller up to `MainActivity.onCreate` — lint's
`UnsafeOptInUsageError` recognises only that annotation, never `@OptIn`.

New UI is written in Compose, **device-verified in both orientations** — three past bugs
were all landscape-only, and a Compose test measures whatever width it is told.

**Two things only a device run catches**, both found after a green suite during the Compose
migration:

- **`painterResource` throws for a `<shape>` drawable** — *"Only VectorDrawables and rasterized
  asset types are supported"*. It killed the app on the first frame rendering a coverless book.
  Draw a placeholder as a `Box` background instead.
- **`AnimatedVisibility` keeps its content composed while hidden.** The expanded player is gated on
  a plain `if (sheetState == EXPANDED)` for that reason — an `AnimatedVisibility` there put every
  per-tick recomposition back behind a collapsed sheet, a measured cost.
  `CollapsedSheetGuardTest` pins it.

## Compose rules

- **`MaterialTheme` defines `colorScheme.background` but paints nothing.** That is `Surface`'s or
  `Scaffold`'s job. A bare `Box` lets the window colour through — the screen renders `#121212` with
  near-invisible text **while every unit test passes**, because the semantics tree is right and
  only the *pixels* are wrong. **Wrap every screen in `Surface`.**
- **`GridCells.Fixed(n)` divides the available width**, so `Fixed(3)` gives 640px cells on the
  1200×1920 tablet and one cover fills the screen. Use `GridCells.Adaptive(minSize)`.
- **Wrap every composable in `ChronicleTheme`** — including previews and tests. Unwrapped renders
  in stock Material purple: obvious on a device, easy to miss in a test asserting only text.
  `ChronicleThemeTest` pins the palette against `colors.xml`; the duplication is deliberate (a
  preview has no Android theme) and retires with the last XML screen.
- **Those version holds are gone.** The Compose BOM, `lifecycle` and `navigation-compose` were
  pinned back because newer releases require **AGP 9.1.0**; the project is on **AGP 9.4.0** since
  2026-09-08, so they now sit at 2026.08.00, 2.11.0 and 2.10.0. The gate was always AGP and never
  compileSdk — 37 shipped under AGP 8 and lifted nothing.

**Navigation Component for Fragments must not be adopted** — Navigation Compose is the target, and
the Fragment variant would be migrated twice. Same reason the Hilt migration follows the screens.

**Notifications and Android Auto stay outside Compose permanently**: a notification is rendered by
the system process from `NotificationCompat`/`RemoteViews`, and the car host draws Auto from
`MediaBrowser` items.

### Five things a green Compose suite will not tell you (all found on a device)

- A **`_white` drawable can carry a black fill** — the name describes the intended tint, not the
  asset. It needs an explicit `tint`, or it renders invisible on a dark surface.
- **`Icon` flattens a multi-colour drawable to a silhouette.** The play button became a bare circle
  with no triangle. Use `Image` for a two-colour asset. This shipped unnoticed in the player and
  was caught only when the same asset reached the details screen.
- **Material3's `labelLarge` does not uppercase**, so a title converted from `android:textAllCaps`
  silently loses its casing. **Screenshot the screen *before* migrating it** — that comparison is
  what caught this.
- A **`LazyColumn` inside a `wrap_content` `ComposeView` throws outright**: *"Vertically scrollable
  component was measured with an infinity maximum height constraints"*. A scrolling Compose body
  must be the `CoordinatorLayout`'s scrolling child
  (`app:layout_behavior="@string/appbar_scrolling_view_behavior"`, `match_parent`), not a view
  inside a `CollapsingToolbarLayout`.
- A **`ComposeView` is clipped by a View parent that sizes it wrong**, and the semantics tree is
  perfectly correct while the pixels are not.

**Extract the shared decision as a pure function before forking a renderer.**
`Audiobook.progressState()` and `chapterRows(chapters, activeChapter)` exist
because two screens render the same thing and must not drift. Doing it first also gives the
behaviour a framework-free test, which is the only kind that can fail for the right reason.

**A `ModalBottomSheet` needs no `expandBottomSheetOnStart()`** — it has no peek state to get stuck
in, which answered the landscape peek-height bug structurally. That helper stays until the last
`BottomSheetDialogFragment` goes.

**`FormattableString` survives Compose.** It looks like a workaround for a `View` being
unable to resolve a string without a `Context`, but the strings are chosen in **ViewModels**, which
still cannot hold one — `SettingsViewModel` alone builds 123. Deferring the `Resources` lookup to
render time is right; `BottomChooser` performs it there.

## The first-frame flash class of bug is structurally gone

It was a Kotlin-driven view with no XML default, holding that default long enough to read "No
libraries found" over onboarding — several sources are cold (a `stateIn(WhileSubscribed)` before
anything collects it, a `combine` waiting on a slow source), so "for a frame" understated it. 34
views were swept, and the *mirror* risk was worse: a view defaulted to `gone` with no writer is
permanently invisible.

A composable renders its state or nothing, so there is no default to flash and no mirror to strand.
`FirstFrameFlashTest` retired with the last layout.

**The lesson that survives is the seed.** A `stateIn` seed that is a *real-looking value* still
renders as one — `FacetList.EMPTY` showed "No narrators yet" before the first grouping ran. That is
why the sealed `Loading` states exist: make the pre-first-emission state
unrepresentable rather than plausible.

**`FormattableString` survives Compose.** It looks like a workaround for a `View` being
unable to resolve a string without a `Context`, but the strings are chosen in **ViewModels**, which
still cannot hold one — `SettingsViewModel` alone builds 123. `BottomChooser` performs the
`Resources` lookup at render time.

## Cover art goes through `CoverImage`

Never a bare `AsyncImage`. `CoverImageTest` fails the build on one, and the reason is that a bare
call sets no `placeholder`/`error`/`fallback`, so a cover that fails to load renders as **nothing**
— a hole in the layout, not a broken image. Six screens shipped that way, because the semantics
tree is correct and only the pixels are wrong.

Three absences, one composable: offline (model null, so Coil never retries an unreachable host), a
book with genuinely no `thumb`, and a failed or undecodable response.

**Two traps this cost.** The screens ask for the raw `/library/metadata/{id}/thumb/{version}` path
through `PlexConfig.toServerString`, while the *notification* builds `/photo/:/transcode` through
`getBitmapFromServer` — so grepping the log for one shape proves nothing about the other. And the
mock server answered thumb paths with an **empty 200**: a bodyless success reads as a served
request in a log, where a 404 would have been one glance.

## StateFlow in the UI

In a composable, collect with `collectAsStateWithLifecycle()`; for a one-shot `Event`, use
`EventEffect` / `ToastEffect` / `ToastResEffect` (`util/compose/EventEffects.kt`), which gate on
STARTED the way `collectEventsWhileStarted` did — a `Toast` raised while backgrounded appears over
whatever the user is looking at, and the event is consumed either way.

Outside a composable — `MainActivity`, a service — `collectWhileStarted(flow) { … }` on the
Activity itself still applies.

- **Never a bare `lifecycleScope.launch`** — it keeps collecting while backgrounded.
- **Never the deprecated `launchWhenStarted`** — it buffers instead of cancelling.
- **`postValue` is banned outright**; `PostValueUsageTest` fails the build on one.

**`stateIn`'s sharing policy is a real choice.** `WhileSubscribed(STOP_TIMEOUT_MILLIS)` is the
default — it survives a rotation without re-running the query. Use **`Eagerly`** when a click
handler reads `.value` *without* collecting: `AudiobookDetailsViewModel.audiobook` has five such
readers, and under `WhileSubscribed` its offline guard read the `null` seed and let an uncached
book reach the player with no server. A test pins that choice.

**Combine with `combineDistinct`** (`util/FlowCombinators.kt`), not a bare `combine` — the
`distinctUntilChanged` is the fix for the recomposition-cost bug, not an optimisation. For a list,
key it with `distinctUntilChangedBy { it.booksKey() }`.

## Orientation and visibility traps

**An `isShown` guard must probe a view that exists in every orientation.** `renderPlayerText`
guarded on `binding.progress`, which carries
`android:visibility="@integer/currently_playing_artwork_visibility"` — GONE in `values-land`. So on
a landscape tablet the guard returned early *every* time and the whole text block stayed blank:
chapter position, duration, percentage and title. It probes `chapterProgressSeekbar` now.

**A modal bottom sheet opens at its peek height in landscape, hiding everything.** The
speed popover rendered *only* its title bar — Material's `BottomSheetDialog` opens collapsed and
expects a drag, and for a `wrap_content` sheet that peek settled at 96px, shorter than the sheet's
own 108px title bar, with nothing suggesting anything was draggable. **Every modal sheet calls
`expandBottomSheetOnStart()`** (`views/ExpandedBottomSheet.kt`) — all three had the bug, only one
had it noticed.

The obvious diagnosis was **wrong**: the task blamed a `wrap_content` `ConstraintLayout` measuring
to zero, but the layout measures 356px in both orientations with or without any fix. **Probe the
measurement before believing a layout explanation.** The `NestedScrollView` + `fillViewport` is a
*separate* need: fully expanded, a window shorter than the content clipped the last control.

**A collapsed sheet keeps its children VISIBLE** — read the container height before diagnosing any
player layout bug.

## Widget traps

- **A `Slider` throws for a value off its step grid.** `setValue` requires an exact multiple of
  `stepSize` above `valueFrom`, so any value coming from outside the UI — a settings import
  validates keys, not values — must be snapped first (`SpeedChooserState.snapToStep`).
- **A `Chip`'s `android:tag` must not be a string resource** when parsed as data: the speed presets
  keyed on `@string/playback_speed_1_0x`, so a locale rendering it "1,0x" matched no branch and
  every preset silently became 1.0x.

## The progress readout is human-formatted

Never `h:mm:ss/h:mm:ss`. `formatCoarseDuration` (`6h 12m`, `<1m`) for a span,
`formatPrecisePosition` (`32:10`) for a position inside a chapter — both in
`util/DurationFormat.kt`, both pure over millis so the wording is testable without a `Context`.
RESEARCH_FINDINGS §3.1 rule 3 is the source; a 47-hour book used to read `47:12:33/52:04:11`.

`RawDurationFormatTest` asserts the four progress views are written from those two and that the
ViewModel exposes no raw pair. It is scoped to the **readout**, not to `DateUtils` — a sleep-timer
countdown genuinely *is* `h:mm:ss`.

## Logging from the UI layer

**Never log a whole collection.** `CollectionLoggingTest` fails the build on a `Timber`
call interpolating a bare collection-shaped name; log a projection (`${books.map { it.id }}`,
`${books.size}`).

`Audiobook.toString()` used to drag in the serialized `chapters` column — one `List<Audiobook>` was
tens of kilobytes, and a measured session produced **3.38 MB across 2920 lines**, built on the main
thread. **A `BuildConfig.DEBUG` guard does not help**: Kotlin builds the interpolated string
*before* `Timber` is called, so a debug build pays the full `toString()` either way.

The check keys on the **name**, not the type, because the two worst offenders had inferred types
only the compiler could resolve. Its real enemy is **plural units** — `Millis`, `Minutes`, `Bytes`
are the commonest plural nouns here and all scalars, so `SCALAR_SUFFIX` excludes them.
