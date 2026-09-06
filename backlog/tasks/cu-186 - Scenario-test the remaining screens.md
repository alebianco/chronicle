---
id: cu-186
title: Scenario-test the remaining screens
status: To Do
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

- [ ] A scenario suite per remaining screen, following the four existing ones
- [ ] Each covers at minimum: reaches RESUMED, renders empty, renders populated, survives recreation
- [ ] **Each suite sabotage-verified** — neuter its mock's `inject` and confirm it fails. This is
      not optional here: a suite written against the *app* graph passes vacuously if the override
      is consulted in the wrong order (cu-178), and that mistake is invisible in a green run
- [ ] `CurrentlyPlayingFragment` and `AudiobookDetailsFragment` last — both are large and
      `MediaServiceConnection`-dependent, so they will need more scaffolding than the rest
- [ ] Unit-test stage stays under a minute; if Robolectric makes it materially slower, record the
      measurement and stop rather than pushing through
- [ ] `./verify.sh` green

## Notes

If cu-181 (Compose) goes ahead, **stop this task at the screens not slated for migration** — a
scenario suite for a Fragment about to be deleted is wasted work. Check cu-181's outcome first.
