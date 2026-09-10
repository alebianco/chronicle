---
id: cu-184
title: Pin the features-to-plex coupling count with a guard
status: In Review
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - maintainability
  - testing
milestone: m-2
dependencies: []
priority: low
ordinal: 93000
---

## Description

CLAUDE.md states that **27** files under `features/` import `data.sources.plex` directly, as the
measure of the known coupling debt (cu-80, cu-33). The real number as of 2026-09-06 is **29**. The `PlexConfig` sub-count has drifted the same
way: documented as 17, actually **19**.

Nothing pins it, so it drifts silently — and a debt number that drifts is worse than no number,
because it is quoted in planning as though it were current. The same class of problem the coverage
ratchet exists to prevent.

Two honest options; **pick one rather than re-measuring by hand**:

1. **A ratchet-style guard** — a test asserting the count does not *rise*, with the current number
   committed as the baseline, so the debt can only shrink. Matches how `coverage-baseline.txt` and
   `FrameworkFreeCoreTest` already work here.
2. **Stop quoting a count** in CLAUDE.md and describe the coupling qualitatively instead.

Option 1 is preferred: the number is genuinely useful for sizing cu-33.1, and a guard makes it
trustworthy. Option 2 is the honest fallback if the guard proves noisy.

## Acceptance Criteria

- [x] **Option 1 taken**: `PlexCouplingRatchetTest` fails when the count rises
- [x] The baseline is a committed file, reviewable in a diff (D12 rule 6) —
      `plex-coupling-baseline.txt`, and it **lists the files rather than counting them**
- [x] Sabotage-verified — adding a `data.sources.plex` import to `SettingsScreen.kt` fails the
      ratchet, naming the file. Restored in a separate call
- [x] The documented numbers corrected — **but to 25, not 29.** See below
- [x] `./verify.sh` green

## Result (2026-09-08)

**Option 1, the ratchet — and the number was wrong again by the time it was written.**

This task recorded a drift from a documented 27 to a measured 29 on 2026-09-06. Measured again on
2026-09-08: **25**. The count moved *down* by four in two days, presumably as the Compose and Ktor
work removed direct Plex reaches.

That makes the case for the guard better than the task argued. The concern was a number quietly
growing; what actually happened is a number moving **in both directions** while three documents
quoted three different values. A stale figure that flatters the codebase is as misleading as one
that maligns it.

**The baseline lists the 25 files rather than storing the count**, which the ticket did not specify
and is the more useful choice: a bare number tells you the ratchet slipped, a list tells you which
file did it, in a diff, at review time. Same shape as `FrameworkFreeCoreTest`.

**Four tests, two of which guard the guard**: the scan reaches the sources (a wrong root would scan
nothing and pass), the baseline is present and populated (a missing file would otherwise read as an
empty set and pass), no file is newly coupled, and no baseline entry is stale — so progress is
recorded rather than merely tolerated, and the ratchet cannot loosen by accumulating dead entries.

`10-tech-stack.md` corrected to 25 with the guard named. CLAUDE.md no longer quotes the figure at
all, so there was nothing to correct there.
