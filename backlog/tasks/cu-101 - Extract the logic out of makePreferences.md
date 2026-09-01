---
id: cu-101
title: Extract the logic out of makePreferences
status: In Progress
assignee: [claude]
created_date: '2026-09-01'
labels: [R2, architecture, debt]
dependencies: []
priority: high
milestone: m-2
---

## Description

`SettingsViewModel.makePreferences()` was 808 lines in one method, with 13 `Injector.get()` calls
and no tests — `features/settings` sits at **0% coverage, 3,886 missed instructions**, the largest
single tractable coverage win in the codebase.

The pre-R2 review named it as the obvious extraction target. Reading it changed the plan, and that
is worth recording: **the 808 lines are mostly not logic.** They are 31 declarative
`PreferenceModel`s with 17 inline listener objects — UI declaration, which a unit test has little to
say about. Extracting it wholesale would move bulk around without buying coverage that means
anything.

What is *worth* extracting is the small amount of real branching hiding inside that declaration:
value↔label mappings written twice, in two `when` chains far apart, each reaching through the
service locator for its strings.

## Implementation Notes (partial — refresh rate done)

**Refresh rate — done** (`afbf221`). Two `when` chains 80 lines apart: one turned stored minutes
into a label, the other turned a chosen option's string resource back into minutes. Nothing tied
them together, so an option the chooser offered but the formatter could not describe would render
as the wrong thing.

`RefreshRate` now holds both directions as one list, making the round trip total by construction,
and `refreshRateLabel` returns a `Named`/`Quantity` description instead of a formatted string.
Resolving the string is the part that needs a `Context`, so only that stays in the ViewModel.

Two latent faults fell out: the formatter's chain ended at `> 60 * 24 * 7` with **no `else`**, so an
unmatched value threw out of the settings screen, and a stored negative did exactly that since the
first test was `== 0L`. Both covered.

808 → 720 lines, coverage 22.06% → 22.36%, 13 tests. Sabotaging the week boundary fails 2 tests
including the round trip.

## Acceptance Criteria

- [x] Refresh rate modelled as one source of truth, with the round trip covered
- [ ] Book cover style (`:193`) — same two-way mapping, currently raw strings `"Square"`/`"Rectangle"`
- [ ] Sync location / storage (`:351`, `:372`) — `bytesAvailable` formatting is real logic
- [ ] Sleep timer and remaining `stringRes`-as-identity `when` blocks (`:544`, `:613`)
- [ ] Resource ids no longer used as option *identity* anywhere in this file — same defect class as
      cu-99, though not a locale bug here since these are ints
- [ ] `features/settings` coverage materially above 0%

## Notes

Deliberately **not** doing: mechanically splitting the 31 `PreferenceModel` declarations into
smaller builders. That is churn against untestable UI declaration, and it would collide with cu-33,
which changes how this class gets its dependencies. Revisit after the carve.
