---
id: cu-101
title: Extract the logic out of makePreferences
status: In Review
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

## Implementation Notes

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

**Book cover style — done.** The same two-way shape, but this one was **actively broken**. The
listener wrote raw literals `"Rectangle"`/`"Square"` while all four consumers compare against
`PrefsRepo.BOOK_COVER_STYLE_RECT` — which is `"Rectangular"`. So choosing the rectangular style
stored a value matching *neither* constant, and `BOOK_COVER_STYLE_RECT` was dead in `main`,
referenced only by a backup test.

It hid because every consumer asks `== BOOK_COVER_STYLE_SQUARE` and treats anything else as
rectangular, so the covers still *looked* right. The visible damage was the settings row, which
interpolated the stored string directly: the chooser offered "Rectangular" and the row then read
**"Book cover style: Rectangle"**.

`BookCoverStyle` now holds `stored` (the persisted English literal, unrenameable — it is in the
backup allowlist) separately from `choiceRes` (the localized label). The row formats via
`ofStoredOrDefault`, so a pre-fix install holding `"Rectangle"` falls back to the default instead
of throwing — the original `when` ended in `throw NoWhenBranchMatchedException`, which is the wrong
answer for a cosmetic preference that is also importable with no value validation ([[cu-133]]).

**Jump intervals — done.** The jump-forward and jump-backward choosers each listed the same six
options and each carried its own resource→seconds `when`, 70 lines apart. No live bug — both copies
agreed — but nothing *made* them agree. `JumpInterval` holds the options once; the caller passes
the fallback (`orElse`) rather than a `forward` flag, because the two directions genuinely disagree
about it (30 s vs 10 s, each mirroring that preference's own default) and a boolean would hide the
asymmetry. Both constants are asserted so they cannot be "tidied" into one wrong value.

**Verified on device** (Samsung SM-A336B, API 36, live Plex server), which is what this criterion
needed since the bug was user-visible:

| step | result |
|---|---|
| seeded legacy `"Rectangle"` into prefs, opened settings | row read **"Book cover style: Square"** — fallback, no crash |
| chose "Rectangular" | stored `Rectangular`, row read "Book cover style: Rectangular" |
| home screen | covers rendered rectangular, so the setting takes effect |
| chose "Square" | stored `Square`, row read "Book cover style: Square" |
| jump-forward chooser | all six options in order; chose 90 s → stored `90`, backward untouched at `10` |

Device prefs were backed up and restored afterwards.

**Sabotage-verified.** Restoring the literal `"Rectangle"` fails 3 of 9 `BookCoverStyleTest` cases
including the one that names the defect; collapsing `DEFAULT_BACKWARD_SECONDS` to 30 fails
`the defaults match the preferences they back`.

**Self-review caught one regression I introduced**: the first version of the cover-style listener
used `?.let`, which silently did nothing on an unrecognized resource where the original threw. Now
it throws, matching the refresh-rate chooser — the options are generated from
`BookCoverStyle.choices`, so an unrecognized resource means a wiring mistake, not user input.

1064 lines (from 1098; 808→720→1064 reflects the added KDoc, the extracted logic left the file).
`features/settings` coverage **0% → 12.22%**; project 30.03% → 30.28%. 22 tests across the three
mapping types.

**Two criteria closed as not-applicable, with reasons:**

- *Sync location / `bytesAvailable` formatting* — `bytesAvailable()` is already extracted to
  `util/StorageUtils.kt`. What remains at the call site is `StatFs` and
  `Formatter.formatFileSize(Context, Long)`, both platform calls a JVM unit test cannot reach.
  Extracting them would move Android API calls behind an interface for no testable logic.
- *Sleep timer* — **there is no sleep-timer preference in this file.** The criterion named it
  speculatively; the sleep timer is [[cu-21]], unstarted.

## Acceptance Criteria

- [x] Refresh rate modelled as one source of truth, with the round trip covered
- [x] Book cover style (`:193`) — same two-way mapping, currently raw strings `"Square"`/`"Rectangle"`
      — and it was **broken**: the stored literal matched neither consumer constant. Verified fixed
      on device.
- [x] ~~Sync location / storage (`:351`, `:372`) — `bytesAvailable` formatting is real logic~~
      **Not applicable**: `bytesAvailable()` is already in `util/StorageUtils.kt`; what is left at
      the call site is `StatFs` + `Formatter.formatFileSize`, platform calls with no testable logic.
- [x] Sleep timer and remaining `stringRes`-as-identity `when` blocks (`:544`, `:613`)
      — the two `when` blocks were the **jump interval** choosers, both replaced. There is **no
      sleep-timer preference in this file**; that belongs to [[cu-21]].
- [x] Resource ids no longer used as option *identity* anywhere in this file — same defect class as
      cu-99, though not a locale bug here since these are ints
      — verified: zero `when (…stringRes)` blocks remain.
- [x] `features/settings` coverage materially above 0% — **12.22%** (559/4576 instructions).

## Notes

Deliberately **not** doing: mechanically splitting the 31 `PreferenceModel` declarations into
smaller builders. That is churn against untestable UI declaration, and it would collide with cu-33,
which changes how this class gets its dependencies. Revisit after the carve.
