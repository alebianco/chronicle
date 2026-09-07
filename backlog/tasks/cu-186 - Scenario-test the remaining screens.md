---
id: cu-186
title: Scenario-test the remaining screens
status: Done
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - maintainability
  - testing
milestone: m-2
dependencies: []
priority: medium
---

## Description

Four screens have `FragmentScenario` suites (`Collections`, `Home`, `Library`, `ChooseServer`).
Ten do not. The recipe is established and blocked on nothing — cu-178 and cu-180 removed every host
cast, so this is mechanical.

## Size it honestly first

**The remaining Fragment-class work is ~219 instructions, not thousands.** cu-178's framing quoted
*9,000 missed instructions, 21% of everything uncovered* — that was the **starting** state, and it
counted the ViewModels and helpers that the extraction tasks (cu-173/174/175/176) and the four
scenario suites have since collected. Measured 2026-09-06, aggregating each Fragment's synthetic
classes:

| screen | missed | covered |
|---|---:|---:|
| FacetBooksFragment | 49 | 0.0% |
| SettingsFragment | 39 | 0.0% |
| CollectionDetailsFragment | 29 | 0.0% |
| CurrentlyPlayingFragment | 24 | 0.0% |
| AudiobookDetailsFragment | 19 | 0.0% |
| ChooseUserFragment | 19 | 0.0% |
| BrowseFragment | 13 | 0.0% |
| SeriesIndexTesterFragment | 13 | 0.0% |
| LoginFragment | 7 | 0.0% |
| ChooseLibraryFragment | 7 | 0.0% |

**So do not sell this as a coverage task** — 219 instructions against 38,504 still missed is ~0.6%.
Its value is *regression protection on screens that have none*, and every one of this project's
worst UI bugs (cu-141, cu-142, cu-19, cu-68) lived in exactly this layer. A suite that catches one
`ClassCastException` on a rotation pays for itself; the ratchet movement will be noise.

**Where the uncovered code actually is**, for anyone chasing the coverage number instead:

| package | missed | covered |
|---|---:|---:|
| `features/player` | 6,987 | 40.5% |
| `features/currentlyplaying` | 4,183 | 27.5% |
| `data/sources/plex` | 3,629 | 55.7% |
| `features/bookdetails` | 3,123 | 33.4% |
| `data/local` | 2,854 | 65.5% |

## Acceptance Criteria

- [x] ~~A scenario suite per remaining screen, following the four existing ones~~ — every target Fragment was deleted; see below
- [x] Each covers at minimum: reaches RESUMED, renders empty, renders populated, survives recreation
- [x] **Each suite sabotage-verified** — neuter its mock's `inject` and confirm it fails. This is
      not optional here: a suite written against the *app* graph passes vacuously if the override
      is consulted in the wrong order (cu-178), and that mistake is invisible in a green run
- [x] ~~`CurrentlyPlayingFragment` and `AudiobookDetailsFragment` last — both are large and
      `MediaServiceConnection`-dependent, so they will need more scaffolding than the rest~~ — both Fragments are gone
- [x] Unit-test stage stays under a minute; if Robolectric makes it materially slower, record the
      measurement and stop rather than pushing through
- [x] `./verify.sh` green

## Notes

If cu-181 (Compose) goes ahead, **stop this task at the screens not slated for migration** — a
scenario suite for a Fragment about to be deleted is wasted work. Check cu-181's outcome first.

## Closing notes — the task's own escape clause fired

The Notes said: *"If cu-181 (Compose) goes ahead, **stop this task at the screens not slated for
migration** — a scenario suite for a Fragment about to be deleted is wasted work."*

Compose went ahead, and it took **all ten**. `find app/src -name '*Fragment*.kt'` returns nothing;
`find app/src/main/res -path '*/layout*'` returns nothing; `buildFeatures.viewBinding` is gone.
Every screen in the table — `FacetBooksFragment` through `ChooseLibraryFragment` — was deleted
rather than tested. So a `FragmentScenario` suite per screen is not deferred work, it is void: the
`launchFragmentInContainer` apparatus the recipe depended on
(`ActivityComponentHost` / `AppComponentHost` / `injectFromHost` / `setToolbarMenu`) has itself
been retired.

The 219-instruction measurement is likewise moot — those instructions no longer exist.

### What the intent still bought

Read as *"screens with no regression test"*, one real gap remained. Eight of the nine Compose
screens already have a `*ScreenTest`; **`PickerScreen` did not**, and it is **four** of the
original ten — the server, library and user pickers are the same list gated on `LoadingStatus`,
and the login step that lists accounts renders through it too.

`PickerScreenTest` now covers it: 7 tests over loading / error / done-with-rows / done-but-empty,
plus the click contract and the error message's content description. That is the same minimum the
criterion asked for (reaches a rendered state, renders empty, renders populated), expressed against
a composable instead of a Fragment; *survives recreation* is the one item that does not translate,
because a `*Screen` is a pure function of state and has nothing to survive.

**Sabotage-verified three ways**, per the criterion that called this "not optional":

| sabotage | result |
|---|---|
| `LOADING` renders the list | fails `loading shows no rows and no error` |
| `ERROR` renders nothing | fails two tests |
| a row reports its `id` instead of its `value` | **passed** — see below |

The third one is why sabotage verification is worth the trouble. It passed because the fixture set
`value = id`, making the two indistinguishable, so the click assertion was proving nothing. The
fixture now gives `id`, `title` and `value` three different strings.

Unit-test stage still well under a minute; `./verify.sh` green.

Closing **Done** rather than In Review: the screens this task named no longer exist, and the
replacement work is a JVM test suite with no device-visible surface. The migration's own device
verification is recorded on the screen tasks.
