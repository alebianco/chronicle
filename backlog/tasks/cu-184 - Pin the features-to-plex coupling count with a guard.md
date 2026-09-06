---
id: cu-184
title: Pin the features-to-plex coupling count with a guard
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - maintainability
  - testing
milestone: m-2
dependencies: []
priority: low
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

- [ ] Either a committed guard that fails when the count rises, or the count removed from CLAUDE.md
- [ ] If a guard: the baseline is a committed file, reviewable in a diff (D12 rule 6)
- [ ] If a guard: sabotage-verified — adding an import to a `features/` file must fail it
- [ ] CLAUDE.md's numbers corrected to 29 and 19 either way
- [ ] `./verify.sh` green
