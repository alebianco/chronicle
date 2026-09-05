---
id: cu-175
title: Split makePreferences into a pure SettingsPreferencesBuilder
status: To Do
assignee: []
created_date: ''
labels:
  - R2
  - maintainability
dependencies: []
priority: medium
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

- [ ] `SettingsPreferencesBuilder` is pure and unit-tested, including the cu-101 label cases
- [ ] `SettingsViewModel` constructor drops to 10 dependencies or fewer
- [ ] The settings screen is unchanged on device — same entries, same order, same labels
- [ ] A preference change still rebuilds the list (the `OnSharedPreferenceChangeListener` path)
- [ ] `features/settings` coverage rises in `coverage-baseline-packages.txt`
