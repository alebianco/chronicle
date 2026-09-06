---
id: decision-22
title: "Adopt Compose for new and migrated UI"
status: Proposed
created_date: '2026-09-06'
---

## Context

The UI is 43 XML layouts (3,808 lines) driven imperatively from 14 Fragments (~3,100 lines). The
View system is in maintenance mode upstream — Google develops Compose and maintains Views — but
that alone is not a reason to migrate a working app.

**The reason is our own bug history.** Four of the hardest-won recent fixes are one failure class:

| task | bug | root cause |
|---|---|---|
| cu-141 | landscape player hid book progress | `isShown` on a collapsed sheet |
| cu-142 | speed popover collapsed to its title bar | `BottomSheetDialog` peek height |
| cu-19 | whole text block blank in landscape | guard anchored on a `values-land` GONE view |
| cu-68 | first-frame flashes; two views permanently invisible | Kotlin-driven visibility with no XML default |

`FirstFrameFlashTest` exists **only** to guard a View-system hazard. cu-178 and cu-180 spent a
session inverting DI and toolbar ownership so `FragmentScenario` could host a screen at all.

## Decision

**Adopt Compose incrementally, screen by screen, via `ComposeView` inside existing Fragments.**
Compose and ViewBinding both stay enabled for as long as any XML layout remains.

## What the POC measured (cu-181)

**The programme-deciding risk does not exist.** cu-181 named Media3/`PlayerView` interop as the
thing that would decide feasibility. Measured: **`PlayerView` appears nowhere in the app, and
`androidx.media3.ui` is imported by no Kotlin file.** The player screen is TextViews, ImageViews, a
Slider and a RecyclerView — an audiobook player has no video surface. `media3-ui` is a declared
dependency that the code does not use.

**Coverage.** 50.92% → 51.75% from one screen. The two new Compose packages seed at **85.83%** and
**85.14%**, against ~40% for the Fragment layer.

**The test story is the real gain.** `CollectionsFragmentScenarioTest` needs
`launchFragmentInContainer`, a mocked `ActivityComponent`, a real factory over four mocks and a
hand-written `SharedPreferences` fake — and can still only assert `view != null`, because reading a
RecyclerView's contents needs Espresso on a device. `CollectionsScreenTest` asserts what is on
screen, with none of that apparatus.

**Contradictory states become unrepresentable.** `CollectionsContent` is sealed, so the screen
renders exactly one of Loaded / Empty / OfflineEmpty. The Fragment decides the same thing with
three independent `isVisible` assignments that can all be true at once.

## Version constraints, found by building

- **Compose BOM held at the 2026.06.x line.** 2026.08.00 pulls Compose 1.12.0, whose
  `material-ripple-android` requires **compileSdk 37**; this project is on 36 (cu-6). Raise the BOM
  only together with compileSdk.
- **`lifecycle-*-compose` reuse the existing 2.10.0 ref.** 2.11.0 wants compileSdk 37 *and* AGP
  9.1.0; AGP 8.x cannot take Gradle ≥ 9.6.0.
- **`activity` 1.8.2 → 1.13.0**, because `activity-compose` upgrades it regardless. In 1.10+
  `onNewIntent` takes a non-null `Intent`.
- **The Compose compiler ships with Kotlin 2.x**, so there is no separate version to keep in step.
- **ktlint** is told `ktlint_function_naming_ignore_when_annotated_with = Composable` rather than
  having the rule disabled, so non-composable functions still must be camelCase.

## Two bugs the tests could not catch, found in ten minutes on the tablet

Both suites were green and the semantics tree was correct while the screen was visibly wrong. This
is the strongest argument in this document for keeping device verification in the loop.

1. **`MaterialTheme` defines `colorScheme.background` but nothing paints it.** That is `Surface`'s
   (or `Scaffold`'s) job, and the screen used a bare `Box` -- so the window's own theme colour
   showed through as `#121212` and the `onBackground` text was near-invisible on it. Every unit
   test passed: the semantics tree was right and only the *pixels* were wrong. Fixed with a
   `Surface`; verified by sampling the framebuffer, which now reads `rgb(45,48,67)` = `#2D3043` =
   `colorPrimary`.
2. **`GridCells.Fixed(3)` divides the available width**, so on a 1920px tablet each cell was 640px
   and one square cover filled the screen. `GridCells.Adaptive(minSize = 180.dp)` asks for a
   minimum cell and picks the count itself -- which is also Android's guidance for adaptive
   layouts, and makes one screen correct on a phone, a tablet and both orientations. A Compose test
   measures whatever width it is told, so no test would have found this.

**Method note:** a screenshot taken before the first frame is a blank window and looks exactly like
a broken screen. Wait for `dumpsys gfxinfo` to report frames rendered, and confirm a suspicious
capture by sampling pixels rather than trusting the eye -- a 15 KB PNG of a 1920x1200 screen is
itself the tell.

## Consequences

- **APK size: +0.1 MB debug** (26.9 → 27.0 MB), measured by building both branches. That is the
  *debug* APK, which is not R8-shrunk; the release delta is the one that matters and is **not yet
  measured**. Compose's own guidance is that R8 strips unused Compose heavily, so the release
  number should be smaller — but that is an expectation, not a measurement, and cu-181 must take it
  before this moves to Accepted.
- **Two UI toolkits at once** until the migration finishes. Accepted deliberately: the alternative
  is a big-bang rewrite of 43 layouts. **Which of these costs actually end** is set out below —
  not all of them do, and the ones that do not are the reason this is a decision rather than an
  obvious yes.
- **Theme values are duplicated** in `ChronicleTheme` as Kotlin literals, because a `@Preview` and a
  Compose test render with no Android theme and `colorResource` would yield stock Material colours.
  `ChronicleThemeTest` pins them against the XML and is sabotage-verified.
- **`FirstFrameFlashTest` and the `isShown` guards become dead** as screens migrate; retire each
  with its screen, not before.
- **Navigation Component must not be adopted for Fragments first.** Navigation-for-Fragments and
  Navigation Compose are different APIs; doing the former now means migrating navigation twice.
  Same argument sequences Hilt (cu-185) after Compose, since `hiltViewModel()` and Compose are
  designed together.

## Which costs are temporary, and which are permanent

Asked directly: does the cost evaporate when the migration completes? **Partly. Three of five end;
two do not.**

### Ends

- **The duplicated theme values.** `ChronicleTheme` mirrors `colors.xml` only because XML screens
  still need the XML palette. When the last XML screen goes, `colors.xml` goes and the Compose
  palette is the single source. `ChronicleThemeTest` retires with it.
- **`FirstFrameFlashTest`, the `isShown` guards, and the whole bug class behind cu-141 / cu-142 /
  cu-19 / cu-68.** These guard hazards that stop existing. **This is the main prize** — not the
  coverage number.
- **`ViewBinding`, the 43 layouts, and the `FragmentScenario` apparatus** (cu-178's
  `ActivityComponentHost` / `AppComponentHost` seams, cu-180's `setToolbarMenu`). All of it exists
  to make Fragments hostable and testable; all of it is deletable. Note `list_item_*` and
  `modal_bottom_sheet_*` layouts migrate too — a row becomes a composable inside `items {}` — so
  the XML count genuinely reaches zero.

### Does not end

- **The APK size.** Compose is a *runtime library shipped in the APK*; the View system is in the
  OS. Removing XML removes almost no bytes, so the delta is close to permanent — R8 shrinks it, it
  does not eliminate it. The measured +0.1 MB debug is the floor of what to expect, and the release
  number is still unmeasured. **The size cost is the price of admission, not a transition cost.**
- **AppCompat and `com.google.android.material` do not leave.** `MainActivity` stays an
  `AppCompatActivity` (theming, day/night, and `ComposeView` needs a host), and 19 Kotlin sites
  reference Material components today. Some of those go; the dependency does not.

### Neither — these are simply out of Compose's reach

Two UI surfaces in this app **cannot** be Compose at any point, and would keep a View-shaped
mental model alive regardless:

- **Notifications.** `DownloadNotificationWorker` and the media notification build
  `NotificationCompat` / `RemoteViews`. A notification is rendered by the *system* process;
  Compose cannot cross that boundary. (Glance exists for App Widgets — this app has none.)
- **Android Auto.** The browse tree and player are drawn by the car's host app from
  `MediaBrowser` items (`onGetRoot` / `onLoadChildren`). We supply data, not UI, and always will.

**So the honest summary:** the *maintenance* costs end and the *bug class* ends — which is the case
for doing it. The *dependency* costs are permanent, and two surfaces stay outside Compose forever.

## Status

**Proposed, not Accepted.**

Done since this was first written: the screen **has** now been rendered on the tablet — that is
where the two bugs above came from — and the debug APK delta is measured.

Still owed before Accepted:

- **Release APK size.** Only the debug APK was measured, and debug is not R8-shrunk.
- **Build-time delta.** Not measured at all.
- **The owner has not seen it.** Launch with:
  `adb shell am start -n io.github.mattpvaughn.chronicle.debug/io.github.mattpvaughn.chronicle.debug.compose.ComposePreviewActivity`
  plus `--es state empty|offline|loaded`.

The composable is **not wired into `CollectionsFragment`**, so accepting or rejecting this changes
nothing a user can see today. cu-181 closes to `In Review` for that reason.
