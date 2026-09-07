---
id: cu-188
title: Migrate the remaining screens to Compose
status: In Review
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - architecture
  - ui
milestone: m-2
dependencies:
  - cu-187
priority: medium
---

## Description

The rest of the migration, once cu-187 has shipped one screen end to end in production.

**One screen per task, split off from this one as each is started** — this is a tracking task, not a
single unit of work. Each split-off task is independently shippable and device-verified.

## Order, by bug density rather than size

The screens that have cost the most debugging go first, because they are where the failure class
decision-22 names actually lives:

| order | screen | Kotlin + XML | bugs recorded against it |
|---|---|---:|---|
| 1 | `CurrentlyPlayingFragment` | 496 + 385 | **cu-141, cu-142, cu-19, cu-110, cu-117** |
| 2 | `LibraryFragment` | 399 + 351 | cu-68, cu-164 |
| 3 | `HomeFragment` | 268 + 188 | cu-68, cu-73 |
| 4 | `AudiobookDetailsFragment` | 402 + 324 | DRAFT-176 |
| 5 | `SettingsFragment` | 180 | cu-77 |
| 6 | the login flow (4 screens) | ~520 | cu-172 |
| 7 | browse / facets / collection details | ~430 | — |

**The player is first on purpose.** It is the largest and the most coupled, so it is the screen most
likely to surface a blocker — and finding that early is worth more than five easy wins. It also
carries five of the recorded bugs. Note `MediaServiceConnection` and `CurrentlyPlaying` are the
awkward part, not the rendering: cu-181 established there is **no `PlayerView`** anywhere, so there
is no `AndroidView` interop to fight.

## What retires as this completes

Delete each with its screen, never before:

- `FirstFrameFlashTest` and every `isShown` visibility guard — they police a hazard that stops existing
- `ViewBinding` (`buildFeatures.viewBinding`), once the last layout goes
- The `FragmentScenario` apparatus: `ActivityComponentHost` / `AppComponentHost` / `injectFromHost` /
  `injectFromAppGraph` (cu-178), `setToolbarMenu` (cu-180), and the scenario suites
- `ChronicleTheme`'s duplication of `colors.xml`, and `ChronicleThemeTest` with it

## Does NOT retire — see decision-22

- The **APK size cost** (Compose ships in the APK; the View system is in the OS)
- **AppCompat / Material** (`MainActivity` stays an `AppCompatActivity`)
- **Notifications** (`NotificationCompat` / `RemoteViews`, rendered by the system process)
- **Android Auto** (the car host draws from `MediaBrowser` items)

## Acceptance Criteria

- [x] A task per screen, in the order above, each shipped and device-verified
- [x] Each migrated screen's Compose tests **sabotage-verified** — cu-181 found that a green suite
      can sit over a visibly broken screen, since the semantics tree is right and only pixels are wrong
- [x] Every screen checked on a device in **both orientations** — cu-141, cu-142 and cu-19 were all
      landscape-only, and a Compose test measures whatever width it is told
- [x] The retirement list above worked through as each becomes dead — **cu-203**, once the last
      layout goes. Nothing on it can retire while any XML screen remains.
- [x] `./verify.sh` green throughout; no coverage regression per screen

## Progress

Screens 1-6 shipped, each on its own task and each device-verified:

| screen | task | status |
|---|---|---|
| player | cu-198 | In Review |
| library + home | (this branch) | shipped |
| details header | cu-200 | In Review |
| settings | cu-199 | In Review |
| login flow | (this branch) | shipped |
| chapter list, shared by player + details | (this branch) | shipped |

Split off, still to do:

- **cu-202** — browse, facets, collection details, search results, series-index tester (screen 7)
- **cu-203** — `BottomSheetChooser`, `BookmarkListAdapter`, then this task's retirement list

**What the migration cost that the plan did not predict.** Four defect classes only a device
showed, all invisible to a green Compose suite: a `_white` drawable carrying a black fill needs an
explicit `tint`; a `ComposeView` is clipped by a View parent that sizes it wrong; `Icon` flattens a
two-colour drawable to a silhouette, so a play button rendered as a bare circle (shipped unnoticed
in the player and only caught on details); and Material3's `labelLarge` does not uppercase, so
`android:textAllCaps` section titles silently lost their casing — caught only by comparing against
a screenshot taken *before* the change.

Two structural lessons worth carrying into cu-202 and cu-203. **Extract the shared decision as a
pure function before forking a renderer** — `Audiobook.progressState()` and
`chapterRows(chapters, activeChapter)` exist because two screens render the same thing and must not
disagree. And **a `LazyColumn` inside a `wrap_content` `ComposeView` throws outright**
("Vertically scrollable component was measured with an infinity maximum height constraints"), so a
scrolling Compose body must be the CoordinatorLayout's scrolling child rather than a view inside a
`CollapsingToolbarLayout`.

The migration also surfaced a real data defect that the View layer had been hiding: a chapter
spanning a track boundary is reported by both tracks and was stored twice, so the fixture book read
"Ch 8 of 10". The old adapter rendered the legacy `Audiobook.chapters` column, empty since cu-49,
so no duplicate could ever reach it. Fixed in `assembleChapters`.

## Notes

Sequencing set by decision-22: **Navigation Component for Fragments must not be adopted** (Navigation
Compose is the target, and doing the Fragment variant first migrates navigation twice), and
**cu-185 (Hilt) follows rather than leads**, since `hiltViewModel()` and Compose are designed together.

## The retirement list, audited 2026-09-07

cu-202 and cu-203 have both shipped (`In Review`), so this criterion is checkable rather than
pending. Measured, not assumed:

| item | state |
|---|---|
| every `res/layout` XML | **0 files.** The one XML the glob catches is `res/color/material_text_input_layout_outline.xml`, a colour selector |
| `buildFeatures.viewBinding` | gone from `app/build.gradle.kts` |
| `FirstFrameFlashTest` | gone |
| `isShown` visibility guards | gone. Three greps survive and none is one: `CollapsedSheetGuardTest` *asserts their absence*, one is a comment quoting the old condition, and `SummaryState.isShown` is a state field on a data class, not `View.isShown` |
| `ActivityComponentHost` / `AppComponentHost` / `injectFromHost` / `injectFromAppGraph` | gone |
| `setToolbarMenu` | gone |
| the scenario suites | gone (see cu-186 — the screens went with them) |
| `ChronicleTheme`'s duplication of `colors.xml`, and `ChronicleThemeTest` | **not retired — see below** |

### The last item cannot be executed as written, and should not be

The list assumed `colors.xml` dies with the last layout. It did not: **the XML palette is still the
authority**, for three consumers that are not screens and are not going away.

- **10 drawables and 3 `res/color` selectors** reference `@color/…` — `ic_play_button_large_colored`,
  `book_cover_missing_placeholder`, `chip_background_color` and the rest.
- **`styles.xml` defines `AppTheme`**, which `AndroidManifest.xml` and the debug manifest set as the
  window theme. `MainActivity` is still an `AppCompatActivity` — which decision-22 explicitly says
  does *not* retire.
- **`ColorContrastTest` reads `colors.xml` as the source of truth** for its WCAG-AA floor,
  including the recorded 4.5:1 reasoning behind `textError = #FF8A80`.

So the duplication is not redundancy left over from the migration; it is a Compose mirror of a
palette the framework still owns. And `ChronicleThemeTest` is the guard that stops the two
drifting — deleting it would leave every Compose screen free to keep an old cyan while every
drawable changed. The original rationale still holds too: a `@Preview` and a Compose UI test render
with no Android theme, so `colorResource` there either fails or silently yields stock Material.

**Retiring this needs `colors.xml` itself to go**, which means porting 13 drawables/selectors and
the window theme — a separate piece of work, not a tail of the screen migration. Filed as a draft
rather than smuggled in here.

### Dead weight found while auditing (also drafted, not done here)

`styles.xml` is now mostly unreferenced. Only **`AppTheme`** and **`FilterChip`** (via `AppTheme`'s
`chipStyle`) are live. `TextAppearance.Body1`, `.Body2`, `.Button`, `.SectionHeader`,
`.RoundedRectInput`, `.SleepTimerCountdown`, `ToolbarTheme`, `ProgressSliderTooltip` and
`Widget.BottomNavigationView` have **zero** references; `TextAppearance.Title`,
`ProgressSliderTextAppearance` and `FilterChipGroup` are referenced only from inside `styles.xml`
itself. Both `values-land/` files are dead — `currently_playing_seekbar_margin_top` and
`currently_playing_artwork_visibility` have no consumer, only a comment in `PlayerScreen.kt`
recording the decision the latter used to encode.

Closing this task **In Review**: the migration it tracked is complete and every retirement item is
either done or shown to be wrongly specified, but the last row is a judgement call the owner should
see rather than a box I can tick silently.
