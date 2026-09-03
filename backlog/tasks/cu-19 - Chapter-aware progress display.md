---
id: cu-19
title: Chapter-aware progress display
status: Done
assignee:
  - '@claude'
created_date: '2026-07-13'
labels:
  - R2
  - comfort
milestone: m-2
dependencies:
  - cu-13
priority: medium
ordinal: 23000
---

## Description

'Ch 8/14 - 32:10 left in chapter - 6h 12m in book' per design brief (RESEARCH_FINDINGS §3.1).

## Acceptance Criteria

- [x] No raw h:mm:ss/h:mm:ss anywhere in the player

## Implementation Notes

The player's progress readout is now two-level and human-formatted, per RESEARCH_FINDINGS §3.1's
convergent-grammar rule 3. On device it reads **`Ch 1 of 10`** · **`0:09 left in chapter`** ·
**`12%`**, where it read `1:21/3:52` before.

### What changed

`util/DurationFormat.kt` — two pure functions, no `Context`, so the wording is unit-testable:

- `formatCoarseDuration` for a **span** (`6h 12m`, `32m`, `<1m`). Nobody tracks a 47-hour book to
  the second, and a seconds field changing once a second is noise on a number meaning "about six
  hours". A whole hour omits the minutes (`6h`, not `6h 0m`); under a minute is `<1m`, not `0m`,
  which reads as a rounding bug on a book that is nearly finished.
- `formatPrecisePosition` for a **position inside a chapter** (`32:10`, `1:02:03`), where seconds
  are what the listener follows. Deliberately not `DateUtils.formatElapsedTime`, which pads to
  `0:32:10` at the hour — that padding is what produced the strings this replaces.

Both truncate rather than round, so a readout never claims more elapsed time than the audio has
played, and both clamp at zero.

`CurrentlyPlayingViewModel.PlayerProgress` carries the *numbers*; the fragment assembles the
sentence from `strings.xml` (rule 5 — translatable, and resources stay in the view layer per rule
2). The derivation is `playerProgressOf`, a **free function**: reaching it through the LiveData
graph needs `audiobookId` to have been populated by the ViewModel's own init observers, which is
plumbing rather than behaviour, so the arithmetic is tested directly instead.

**Five now-dead properties removed** from the ViewModel: `progressString` (the literal
`"$progressStr/$durationStr"` the criterion names), `chapterProgressString`,
`chapterDurationString`, `trackProgress`, `trackDuration`.

The seekbar's scrub tooltip also moved onto `formatPrecisePosition` — same information, and it was
the last `DateUtils` call in the readout. The sleep-timer countdown keeps `DateUtils`, because a
countdown genuinely *is* `h:mm:ss`.

### A live bug found by verifying on the device rather than trusting the diff

**`renderPlayerText()` never ran in landscape.** Its guard was
`if (!binding.progress.isShown) return`, and that view carries
`android:visibility="@integer/currently_playing_artwork_visibility"`, which `values-land` sets to
GONE. So on a landscape tablet the guard returned early *every* time and the chapter position,
chapter duration, percentage **and chapter title** were all blank — the whole text block, not just
the hidden line. cu-117 introduced the guard for a good reason (skip the work while the sheet is
collapsed) but anchored it on a view one orientation hides outright.

Re-anchored on `chapterProgressSeekbar`, which is present in both orientations and is what
`refreshSlider` already probes. The layout-change re-render hook moved with it, or it would watch
a view that never becomes shown.

The remaining half — that `binding.progress` is *still* hidden in landscape — is
**[[cu-141]]**, filed rather than folded in: it needs a landscape constraint set because the view
is constrained to `details_artwork`, which genuinely does not exist there. cu-19's criterion is
about the readout's *format*; that is a layout question.

### A fixture defect my own cu-18 change exposed

The player first read **`Ch 1 of 9`** for a 7-chapter book. Not a cu-19 bug: `retrieveChapterInfo`
is read with `metadata.firstOrNull()`, and `track-with-chapters.json` held **all three** tracks —
so every track received *track 2001's* three chapters and each was stored three times. cu-18 fixed
the album half of this routing and left the track half; both fixture servers now serve
`track-<id>-chapters.json` per track id.

The count is 10, not 7, and that is correct: chapters 3 and 5 span track boundaries and the
fixture reports them on both tracks, exactly as Plex does.

### Guard against the format coming back

`RawDurationFormatTest` asserts each of the four progress views is written from the human
formatters and that the ViewModel exposes no raw pair. **Scoped to the readout, not to `DateUtils`
generally** — a first cut banned the call outright and flagged three legitimate uses (the
sleep-timer countdown, the scrub tooltip, a `Timber` log), which would have been a check nobody
could keep green. Sabotage-verified: putting `DateUtils.formatElapsedTime` back on
`binding.progress` fails with *"progress must not be written from DateUtils"*.

### Verification

- `./verify.sh` green, 7 stages. **801 → 823 unit tests**, 0 failures (25 added: 9 formatting,
  9 derivation, 6 guard, 2 fixture contract; 3 LiveData-graph tests were replaced by direct ones).
- Coverage 32.24% → 32.52%; `util` 38.81% → **44.03%**, `features/currentlyplaying` 18.60% →
  **19.93%**.
- **On device** (tablet, mock mode, the multi-track fixture): the player shows
  **`0:02 left in chapter`** under the seekbar and `13%`, with no raw `h:mm:ss/h:mm:ss` anywhere.
  `Ch 1 of 10` is written correctly — the accessibility dump reports `text: Ch 1 of 10` on
  `chapter_progress` — but lays out at **zero width** in this landscape layout, which is
  [[cu-141]]'s second criterion. The chapter title and book title also render now; they were blank
  before the `isShown` fix.
  The fixture routing log shows one `album-1001.json` and one `track-200x-chapters.json` per track.
  Note a `uiautomator dump` **fails during playback** ("could not get idle state") and a failed
  dump leaves the previous file in place — so every dump here asserts the file exists first, after
  pausing. An earlier read without that assertion reported "no time text", which was a stale file.

### Out of scope, deliberately

The library shelves also print raw `h:mm:ss/h:mm:ss` (`AudiobookAdapter.formatProgress`). The
criterion says *"anywhere in the player"*, and widening it to every surface would turn this into a
UI sweep. Left as a decision rather than an omission; the formatters are there when a shelf task
wants them.
