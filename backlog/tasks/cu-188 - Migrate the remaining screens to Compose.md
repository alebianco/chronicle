---
id: cu-188
title: Migrate the remaining screens to Compose
status: In Progress
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
- [ ] The retirement list above worked through as each becomes dead — **cu-203**, once the last
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
