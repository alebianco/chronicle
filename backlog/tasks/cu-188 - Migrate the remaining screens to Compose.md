---
id: cu-188
title: Migrate the remaining screens to Compose
status: To Do
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

- [ ] A task per screen, in the order above, each shipped and device-verified
- [ ] Each migrated screen's Compose tests **sabotage-verified** — cu-181 found that a green suite
      can sit over a visibly broken screen, since the semantics tree is right and only pixels are wrong
- [ ] Every screen checked on a device in **both orientations** — cu-141, cu-142 and cu-19 were all
      landscape-only, and a Compose test measures whatever width it is told
- [ ] The retirement list above worked through as each becomes dead
- [ ] `./verify.sh` green throughout; no coverage regression per screen

## Notes

Sequencing set by decision-22: **Navigation Component for Fragments must not be adopted** (Navigation
Compose is the target, and doing the Fragment variant first migrates navigation twice), and
**cu-185 (Hilt) follows rather than leads**, since `hiltViewModel()` and Compose are designed together.
