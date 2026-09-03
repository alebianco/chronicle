---
id: m-1
title: "R1 — Trust"
---

## Description

"Never lose my place, never fail my download." Reliability core: progress-reporting overhaul, 401 re-auth, connection tiering, download rebuild, chapter correctness, drift check, the ride-along refactor + MediaSource seam, Plex fixture pack, backup framework, security + test-coverage backfill.

## Archived 2026-09-03 — 55/55 tasks Done

All 55 tasks moved to `backlog/completed/` (`backlog task complete`), clearing them from the
Kanban board, and the milestone was archived.

**The count is written here by hand because the CLI can no longer compute it.** A milestone's
completion figure is derived from task files, so once they move it reports `0/0` and appears under
*Active* — reading as an empty milestone available for reuse rather than a finished one. That is
the whole reason for archiving it (same as [[m-0]]).

Accepted trade-off: the board is clean and the milestone cannot be reused, at the cost of R0 and R1
no longer appearing on the milestones page with their completion figures. Verified there is no CLI
setting that gives both — task file location drives both behaviours, and moving a single task back
flips *both* at once.

The tasks are not lost: `backlog task cu-<n>` still resolves, `grep -r` across `backlog/` still
finds them, and wiki-links from live tasks still point at real files. Only `backlog search` skips
`completed/`.
