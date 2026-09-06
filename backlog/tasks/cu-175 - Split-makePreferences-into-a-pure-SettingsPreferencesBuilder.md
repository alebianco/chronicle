---
id: cu-175
title: Split makePreferences into a pure SettingsPreferencesBuilder
status: In Review
assignee: []
created_date: ''
labels:
  - R2
  - maintainability
dependencies: []
priority: medium
milestone: m-2
---

## Status note (2026-09-05): rescoped — the premise was wrong

This draft argued the split was needed because 15 dependencies made the class untestable. It is
not: nothing calls the service locator, `init` only registers a prefs listener, and it constructs
from fifteen mocks. The real blocker was `makePreferences` reading a string resource per row during
construction — solved by `@RunWith(RobolectricTestRunner::class)`, one annotation.

`SettingsViewModelTest` now covers it (0% → 47.6%), so the refactor is no longer an unblocker and
should not be justified as one. **It remains worth doing for readability** — a 737-line function is
hard to review, which is the finding that actually stands — and it now has tests to refactor
against, which is the right order.

## Description

`SettingsViewModel` has **15 constructor dependencies** — the most in the codebase, against a
median of 4:

```
15  SettingsViewModel          ← this task
11  CurrentlyPlayingViewModel
 9  AudiobookDetailsViewModel
 …
 2  LoginViewModel, FacetBooksViewModel
```

It owns preferences rendering, library management, cache management, login/logout, settings
import/export, sync-location moves and the licence screen — seven responsibilities.

`makePreferences()` is **737 lines (CC 30)** building 36 `PreferenceModel` entries, rebuilt
wholesale on every preference change via `OnSharedPreferenceChangeListener`.

The length itself is *not* the problem — it is declarative UI description, reads linearly, and
splitting it by line count would make it worse. The problem is that it sits inside a class with
15 dependencies, so it cannot be tested without constructing all of them.

**Proposed cut:** `SettingsPreferencesBuilder` taking `PrefsRepo` + a string resolver and
returning `List<PreferenceModel>`. Two dependencies, not fifteen, and immediately unit-testable.
Settings *actions* (export/import, cache, sync-location) stay in the ViewModel.

**Precedent already in the file:** cu-101 pulled `refreshRateLabel` and the `BookCoverStyle`
decision into pure tested files, leaving only the `Context` string lookup behind
(`formatRefreshRate`, `formatBookCoverStyle`). This is the same move applied to the whole builder,
and those two helpers move with it.

See `backlog/docs/analysis/maintainability-review-2026-09.md`.

## Acceptance Criteria

- ~~[ ] `SettingsPreferencesBuilder` is pure and unit-tested~~ — **retired**: the split was made by *section*, not by label-vs-handler. A pure builder would have moved the labels out and left all 20 handlers in the ViewModel, covering nothing. See the notes below.
- ~~[ ] `SettingsViewModel` constructor drops to 10 dependencies or fewer~~ — **retired**: the handlers genuinely use ten collaborators, so the count cannot fall without moving *behaviour*, which is a product decision about what belongs on this screen rather than a refactor.
- [ ] The settings screen is unchanged on device — same entries, same order, same labels **(not device-verified)**
- [x] A preference change still rebuilds the list (the `OnSharedPreferenceChangeListener` path)
- [x] `features/settings` coverage rises in `coverage-baseline-packages.txt` — 19.00% → 59.29%

## Attempt notes (2026-09-05) — coverage delivered, extraction deferred

The coverage half of this task is **done**; the refactor is **not**, and they turned out to be
independent.

`SettingsClickHandlerTest` exercises the click handlers by pulling rows out of the list
`makePreferences` builds and invoking `row.click.onClick()` — which is exactly what a tap does. No
extraction was needed for that, and this is the key finding:

> **A `SettingsPreferencesBuilder` would move the *labels* out and leave every handler behind in
> the ViewModel.** The 231 uncovered lines inside `makePreferences` are the handlers, so the
> refactor would not have covered them.

`features/settings` went **43.14% → 59.29%** with no production restructuring.

### It found a real defect

The sync-location row built its chooser options from `externalDeviceDirs`, which
`provideExternalDeviceDirs` filters nulls out of (cu-85). A device with **no available volume** —
all ejected, or an emulator — therefore opened a dialog containing **nothing**, with no way out but
the back button. It now reports the situation instead. Sabotage-verified.

One assertion was narrowed rather than kept: the first version required every sheet to offer more
than one option, which flagged the **credits** row. That row opens a single-body informational
sheet, which is a different thing from a choice, and is correct as it stands. The rule is now "no
row opens an *empty* sheet".

### What is left

The actual extraction — 36 `PreferenceModel` entries, 20 carrying handlers that touch ten
collaborators. It is a large mechanical rewrite of a 737-line function on a screen that cannot be
verified without a device, so it is **not** something to do unattended at the end of a long
session. It now has tests to refactor against, which is the right order, and its justification is
readability alone.

Returned to `To Do` rather than closed.

## Implementation Notes (2026-09-05) — split by section, not by concern

Done, but **not** the split this task originally proposed, and the difference is the finding.

A pure `SettingsPreferencesBuilder` would have moved the *labels* out and left all 20 click
handlers behind in the ViewModel. Since the handlers are where the uncovered lines were, that split
would have solved neither problem. `SettingsViewModel` still needs its fifteen dependencies because
the handlers genuinely use ten of them.

What actually helps readability is splitting by the **sections the screen already has** — the six
`TITLE` rows were the natural seam:

| function | lines | CC |
|---|---:|---:|
| `makePreferences` | **86** | **7** |
| `accountPreferences` | 201 | 17 |
| `syncPreferences` | 162 | 5 |
| `playbackPreferences` | 142 | 1 |
| `appearancePreferences` | 72 | 3 |
| `etcPreferences` | 58 | 2 |
| `backupPreferences` | 51 | 2 |

From one 748-line function at CC 30. `makePreferences` now reads as six `addAll` calls plus the
debug-build and Android Auto post-processing it always had.

The existing tests carried the refactor — they passed unchanged throughout, which is the whole
reason the tests came first.

`accountPreferences` at CC 17 is the one still worth attention; it holds the login/logout and
library-switching handlers. Not split further here because that is a judgement about *product*
grouping rather than mechanics.

**Left `In Review`:** the settings screen was not verified on a device. The row list is unchanged
by test, but only an owner's eye confirms nothing shifted visually.

### Not done, deliberately

The constructor still takes 15 dependencies. Reducing that means moving handlers, which means
moving behaviour — a different task, and one that should follow a product decision about what
belongs on this screen.
