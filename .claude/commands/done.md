---
description: Run the Definition of Done for the current change before closing a task
argument-hint: "[task-id]"
allowed-tools: Bash(./verify.sh:*), Bash(./setup-repo.sh:*), Bash(./list-build-gates.sh:*), Bash(git status:*), Bash(git diff:*), Bash(command grep:*), Read, Edit, Agent
---

Close out **$ARGUMENTS** properly. Work through the Definition of Done in order and report each
step's real outcome — never claim a step passed without its output.

## 1. The gate

```bash
./verify.sh
```

This *is* the definition of "the build is fine" — not CI. All six stages must pass: ktlint, unit
tests, the coverage ratchet, the debug APK, lint, and **the release variant compiling**.

If the ratchet moved coverage up, commit the changed baseline file. If it moved down, that needs
justifying in the commit message — do not lower it silently.

## 2. Tests exist for what changed

D6 requires tests for touched repositories, ViewModels, and sync/download/chapter logic. Check the
diff actually adds or extends them. A new guard must be **sabotage-verified** — and remember
Gradle's up-to-date checks make a sabotaged test look like it passed, so use `--rerun-tasks` and
restore in a separate call.

## 3. Self-review

Launch the `self-review` subagent on the diff. It reviews against the constitution and the known
defect classes, and it does not edit. Act on anything it rates **must fix** before continuing.

## 4. Docs synced in the same change

- `backlog/docs/reference/` if architecture or behaviour changed
- `CLAUDE.md` if any statement there became false
- The task file's own status and criteria

## 5. The status decision — this is the one that gets it wrong

**The question is not "feature or bug", it is: can a machine prove this was right?**

`Done` only when the proof is automated — a test, a build gate, a measurement a script reproduces.

`In Review` when the work **changed a screen**, made a **product or design choice** the owner might
want differently, or has an **acceptance criterion that is a visual/on-device check you did not
perform**. In that last case leave the box unticked *and* the status `In Review`.

When setting `In Review`, write in the task file **what specifically needs the owner's eye** — "the
shelf's sort order", not "please review".

A criterion that turned out to be **wrong** rather than unmet is retired with its reasoning, never
silently ticked.

## 6. Close the file

Replace `## Implementation Plan` with `## Implementation Notes`: what actually changed, decisions
taken, follow-ups. Promote any unfinished item to a **task** (`status: To Do`), not a draft — a
deferred item filed as a draft and linked from a closed task is invisible in every normal view, and
that is how work has been lost here. Say in the notes where each item went.

## 7. Commit

Scoped Commits: `<scope>: <description>`, body explaining *why*, `Task: $ARGUMENTS` trailer. The
scope is the subsystem, never the task id. **No `Co-Authored-By`, no `Claude-Session`, no
"Generated with" footer** — a hook blocks these, but write them out of the message in the first
place.

## Report

State plainly: verify result, what the self-review found, the status you set **and why**, and
anything you deliberately left undone. If a step failed, say so with its output rather than
proceeding.
