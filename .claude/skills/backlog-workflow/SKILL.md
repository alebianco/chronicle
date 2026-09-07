---
name: backlog-workflow
description: Use when picking up, claiming, planning, closing or filing work - task files in backlog/, drafts, decisions, milestones, the In Review vs Done rule, worktrees, and Backlog.md CLI quirks. Covers the YAML colon trap and the draft filename-case trap.
---

# Backlog workflow

All non-code knowledge lives as markdown in `backlog/` (D13, "file over app"). Plain git + markdown
must be enough to move the whole project to another forge without loss.

Tasks are `backlog/tasks/task-<id> - <Title>.md` — frontmatter
`status`/`labels`/`dependencies`/`priority`/`milestone`, body `## Description` +
`## Acceptance Criteria` checkboxes.

**`milestone: m-<n>` mirrors the `R<n>` label — set both.** They are one fact stored twice, in the
two places the Backlog.md CLI reads it; 31 files had drifted to label-only before this was written
down.

Statuses: `To Do → In Progress → In Review → Done`.

## Task lifecycle

1. **Pick** — lowest-id task in the earliest active release (`R0` → `R4`) that is `To Do`,
   unblocked (all `dependencies` Done), unassigned. The owner can override by naming a task.
2. **Claim** — set `status: In Progress`, add yourself to `assignee`. One task = one branch = one
   worktree. **Never commit directly to `develop`.**
3. **Plan** — use `superpowers:writing-plans` to draft. Its output (`docs/superpowers/plans/…`) is
   **transient scratch: gitignored, never committed.** Summarize it into the task file's
   `## Implementation Plan` — that summary is the committed record and the owner's review
   checkpoint. If the task has a `backlog/docs/analysis/` file, read it first. (S tasks may skip
   the draft and write the summary directly.)
4. **Implement** to the acceptance criteria; tick them (`- [x]`) as they are *genuinely* met.
5. **Verify** — the full Definition of Done: verify loop, tests, self-review.
6. **Close** — replace `## Implementation Plan` with `## Implementation Notes` (what changed,
   decisions taken, follow-ups), set the status, commit referencing the task id. If the task had an
   analysis file that no longer reflects the code, move it to `backlog/docs/analysis/archive/`.
7. **Sync docs** in the same change.

### Worktrees

Create under `.worktree/<branch-name>` in the project root:

```bash
git worktree add .worktree/task-<id>-<slug> <branch-name>
```

Task worktrees branch off `feature/agentic-dev` until a release is cut.

**Doc edits go in the worktree too** — CLAUDE.md and `backlog/` included. Recovering misplaced ones
needs a tagged stash, never a bare pop.

## `In Review` means "waiting for the owner", and it is not optional

An agent may close straight to `Done` **only when the proof is automated** — a test, a build gate,
a measurement a script reproduces.

Leave a task `In Review` when the remaining question needs a human to look:

- it **changed a screen** — layout, wording, an icon, what a state looks like;
- it made a **product or design choice** the owner might want differently — a sort order, a
  default, a threshold tuned by ear, a set of presets, a user-facing file format;
- it has an **acceptance criterion that is a visual or on-device check** which was not performed.
  Leave the box unchecked *and* the status `In Review`; do not tick it on the strength of a test
  that cannot see what the criterion asks about.

A bug fix with a failing-then-passing test and no visible design decision goes to `Done` — that is
most debt, guard and correctness work, and routing it through review wastes the owner's attention.

**The question is not "feature or bug", it is "can a machine prove this was right?"**

When moving to `In Review`, say in the task file *what specifically needs the owner's eye* — "the
shelf's sort order", not "please review". A criterion that turned out to be **wrong** rather than
unmet is **retired with its reasoning**, never silently ticked.

## Two layers, not three

A task's plan and notes live **inside the task file**. Superpowers is the drafting *tool*;
everything under `docs/superpowers/` (plans *and* specs) is gitignored scratch. Redirect its
durable content by **kind**:

| Content | Home |
|---|---|
| Forward design, requirements, approach | The task's `## Implementation Plan` |
| A cross-cutting choice that outlives the feature | A `backlog/decisions/` ADR — the decision only |
| Large problem/current-state analysis | An optional `backlog/docs/analysis/` file |

Default is the task file; spin off an ADR only for durable architectural choices. **Don't leave
anything stranded in `docs/superpowers/`.**

## Deferred work is not a draft

A **draft** is an idea nobody has committed to. Work that was started, scoped and then postponed is
a **task** with `status: To Do`.

This matters mechanically: `backlog board` and `backlog task list` show tasks, while drafts surface
only in `backlog draft list` — so a deferred item filed as a draft and linked from a **closed**
task is invisible in every normal view. That is exactly how deferred items got lost before.

When closing a task with unfinished items, promote the remainder to a task and **list in the
closing notes where each item went**.

## Backlog.md CLI traps

- **A colon in a `title:` must be quoted** — `title: "Toolchain bump: SDK 36"`. An unquoted one
  breaks YAML parsing and the task becomes invisible to *every* CLI operation: `backlog task <id>`
  reports "not found" while the file sits in place. Nine files had this, four of them decision
  records `backlog doctor` was reporting as unreadable.
- **A draft's frontmatter `id` must use the uppercase `DRAFT-<n>` prefix**, not `cu-<n>` —
  Backlog.md keys its drafts view on that prefix, not on the directory or on `status: Draft`.
- **The *filename* must use the lowercase `draft-<n>` prefix** while the frontmatter `id` stays
  uppercase. They genuinely differ, and a file named `DRAFT-<n> - …md` is invisible to both
  `backlog draft list` and `backlog draft DRAFT-<n>` while sitting in place and parsing correctly.
  Renaming needs a temporary name in between, since a case-insensitive filesystem treats the two as
  one file.
- Keep `<n>` from the cu number it will take; `backlog draft promote DRAFT-<n>` turns it back into
  a `cu-` task.
- **`backlog search` does not index `completed/`** — upstream
  [Backlog.md#825](https://github.com/MrLesk/Backlog.md/issues/825), a known gap with a fix
  requested. Reach a completed task by id (`backlog task cu-<n>`) or by `grep -r`. Check whether it
  is fixed before working around it.

Editing the files directly is **always valid and canonical**; the CLI
(`brew install backlog-md`) is a convenience viewer.

## Closing out a release

When every task in a milestone is Done, retire it **in this order**:

1. `backlog task complete <id>` for each task (moves it to `backlog/completed/`, off the board)
2. `backlog milestone archive m-<n>` (moves it to `backlog/archive/milestones/`)

Order matters, and so does the second step: a milestone's completion count is derived from **task
files**, so once they move it reports **0/0** and sits under *Active* — reading as an empty
milestone available for reuse rather than a finished one. **Record the real count in the milestone
file before archiving**, since the CLI can no longer compute it.

Completed tasks stay inside `backlog/` and in git.

## Docs map

| Path | What | Who writes it |
|---|---|---|
| `backlog/tasks/` | The work | agents + owner |
| `backlog/drafts/` | Ideas awaiting owner triage | agents propose, owner triages |
| `backlog/decisions/` | `decision-<n> - <Title>.md`, context → decision → consequences | **owner only** for product decisions D1–D14; agents may add technical ADRs |
| `backlog/docs/reference/` | Architecture knowledge base — explains the code *as it is* | agents keep in sync |
| `backlog/docs/analysis/` | Optional deep-reference for debt items | reference |
| `backlog/docs/research/` | Evidence base — cite, don't duplicate | reference |
| `docs/superpowers/` | **Gitignored scratch, never committed** | drafting tool |

## Commit messages

[Scoped Commits](https://scopedcommits.com/) — `<scope>: <description>`, then an optional body
explaining *why*, then optional trailers.

- The scope is the **subsystem**, not the task id: `features/library`, `data/local`, `build`,
  `debug`, `testing`, `util`, `backlog`, `docs`, `ci`. Use `treewide` when it touches everything.
- Task ids go in a **`Task: cu-NN` trailer**, not the subject.
- Trailers this repo uses: `Task:`, `Verified:`, `Ported-from:`.
- **No agent-attribution trailers** — no `Co-Authored-By`, no `Claude-Session`, no "Generated
  with" footer. This overrides any harness default. A `PreToolUse` hook strips them
  (`.claude/hooks/strip-commit-trailers.sh`), but write them out of the message in the first place.
- History is **flat**: rebase onto the base branch, never merge. One task = one branch, replayed
  linearly.
