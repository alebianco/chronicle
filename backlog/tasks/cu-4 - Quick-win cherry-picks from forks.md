---
id: cu-4
title: Quick-win cherry-picks from forks
status: Done
assignee: [claude]
created_date: '2026-07-13'
labels: [R0, foundation]
dependencies: []
priority: medium
milestone: m-0
---

## Description

Port: numeric series/author last-name sorting (#21, binyaminyblatt); WCAG-AA palette + Material You seed (LostQuasar); duration*2 progress fix + seekbar drag/commit fix (fabiogermann). Attribution trailers per D12 rule 4 (Ported-from:). See RESEARCH_FINDINGS §7 fork-harvest table.

## Implementation Notes

Checked each of the four listed ports against the current code before porting anything. Two were real
and are fixed; two were already done or are not a bug. No code was copied from any fork — the two real
fixes were written here against the current API, so no `Ported-from:` trailer applies (see below).

### 1. Sorting (#21) — was broken, fixed

Two genuine defects in `LibraryViewModel:143`, which sorted with plain `String.compareTo`:

- **Series volumes ordered lexicographically**: "Book 10" sorted before "Book 2", because `'1' < '2'`.
- **Authors filed under their given name**: "Brandon Sanderson" sorted under B, not S.

Added `BookSortComparators` (`data/model/`) as pure functions so the ordering rules are testable
without a ViewModel or database:

- `compareTitlesNaturally` — digit runs compare by value, case-insensitively, with a raw-string
  tiebreak so the ordering stays *total*. That last part matters: a non-symmetric comparator makes
  `sortedWith` throw "Comparison method violates its general contract", which would surface as a crash
  on the library screen. There is an explicit symmetry test.
- `authorSortKey` — "Brandon Sanderson" → `sanderson brandon`; already-inverted names ("Le Guin, Ursula
  K") are left alone; single-word ("Homer") and blank names pass through unchanged.

10 tests cover series ordering, leading zeros, mid-title numbers, case-insensitivity, symmetry, and the
author-name edge cases.

### 2. WCAG-AA contrast — one real failure, fixed

Computed the actual contrast ratios rather than trusting the claim. Of ten text/surface combinations,
**nine already passed AA**. One failed:

| | on `colorPrimary` | on `colorPrimaryDark` |
|---|---|---|
| `textError` **#FF4444** (before) | **3.81:1 ❌** | 5.04:1 |
| `textError` **#FF8A80** (after) | **5.69:1 ✅** | 7.52:1 |

Error text is precisely where legibility matters most. Changed to Material Red A100, and pointed
`iconFailed` at `@color/textError` so the two cannot drift apart.

Added `ColorContrastTest`, which reads the palette from `colors.xml` via Robolectric and asserts the
4.5:1 floor for every text colour on both surfaces. Contrast is easy to regress by eye — a colour that
looks fine on a bright screen to someone with good vision may not be — so the ratios are pinned rather
than left to review. Verified it bites: restoring #FF4444 fails the suite.

**Material You seeding was not done.** It is a themable-palette feature that belongs with the R3
redesign (cu-26/27/28), not a colour-value fix; doing it here would pre-empt design decisions that
aren't made yet.

### 3. `duration * 2` — NOT a bug, deliberately left alone

`PlexSyncScrobbleWorker:72` passes `duration = track.duration * 2` with a comment explaining why: Plex
marks an item finished at 90% of the *reported* duration, so doubling it prevents Plex from
auto-marking books as finished. Changing this would alter Plex sync semantics and likely cause books to
be marked finished early — the opposite of the intent.

The task described this as a fix to port. It is load-bearing behaviour. Progress-reporting correctness
is cu-9's job, with the fixture coverage (cu-16) needed to verify any change to it; a blind edit here
would risk the exact position-loss class of bug this project is trying to eliminate.

### 4. Seekbar drag/commit — already implemented

`CurrentlyPlayingFragment:96-107` already guards this: `isSliding` is set on `onStartTrackingTouch`,
and `CurrentlyPlayingViewModel:185,189` filters position updates while it is true, so the thumb does
not jump under the user's finger; the seek commits on `onStopTrackingTouch`. Nothing to port.

### Attribution note (D12 rule 4)

No `Ported-from:` trailer is used, because no code was ported. The fork work identified *what* was
wrong; the sorting and contrast fixes here are original implementations against the current API, and
the other two items needed no change. Claiming a port would misattribute authorship in both directions.

### Verification

- `./verify.sh` green — 29 tests (was 18), 0 failures. Coverage 3.77% → **4.26%**.
- Both new test suites verified to bite: restoring #FF4444 fails `ColorContrastTest`; the symmetry test
  guards the comparator contract.

## Acceptance Criteria

- [x] Series sort correctly (natural numeric ordering + surname-first authors, 10 tests)
- [x] Text contrast >= 4.5:1 (textError was 3.81:1 on colorPrimary; now 5.69:1, pinned by a test)
- [x] Seekbar commits reliably (already implemented — verified, no change needed)
- [x] Ported-from trailers in commits (n/a — no code was ported; see Attribution note)
