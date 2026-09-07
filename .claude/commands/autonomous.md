---
description: Work a batch of tasks unattended for hours without stopping — park blockers, keep going, report honestly at the end
argument-hint: "[how much, e.g. 'the next 10 tickets' or 'until R2 is clear']"
allowed-tools: Bash, Read, Grep, Glob, Edit, Write, Agent, Workflow
model: opus
---

Work autonomously: **$ARGUMENTS**

## The one rule that matters

**No stopping means no stopping.** The only explicit rebuke in this project's history is exactly
this:

> *"dude you stopped at the first, I said no stopping. if something needs to be parked you leave it
> uncommitted on a worktree or commit it on a side branch to pick up later. resume and move on to
> the next tasks. do better."*

So when a task blocks:

1. **Park it** — leave it uncommitted on its worktree, or commit it to a side branch.
2. **Write down where it is and why** — one line, for the final report.
3. **Start the next task immediately.**

You do not stop to ask. You do not stop to report progress. You do not stop because a task turned
out harder than expected. The owner's tolerance for *"I didn't reach all of them"* is high; his
tolerance for stopping at task one is zero.

## Picking the batch

Lowest-id, unblocked (`dependencies` all `Done`), unassigned, earliest active release (`R0` → `R4`).

**Front-load what can actually finish.** Drop from the batch anything whose acceptance criteria are
mostly `(device)` or visual — you have no tablet, so those cannot close; note them for a
`device-verifier` pass instead. A task needing a product decision to *start* is parked, not guessed.

State the batch before you begin. Then begin.

## Per task

One task = one branch = one worktree under `.worktree/<branch-name>`, branched off
`feature/agentic-dev` ([[decision-23]]). **Never commit to `develop`.** Run `./setup-repo.sh` in a
fresh worktree first, or every Gradle task fails with "SDK location not found".

Implement test-first to the acceptance criteria. `./verify.sh` green is the definition of done —
ktlint, tests, coverage ratchet, debug APK, lint, **and the release variant compiling**. A new
guard test is **sabotage-verified** (`--rerun-tasks`, restore in a separate call).

Commit as `<scope>: <description>` with a `Task: cu-NN` trailer. No `Co-Authored-By`, no
`Claude-Session`, no "Generated with" footer.

Set the closing status by the one question — **can a machine prove this was right?** `Done` if yes
and no screen changed and no product choice was made; otherwise `In Review` with a specific note
about what needs the owner's eye.

## When to use a subagent instead of doing it yourself

- A product question → `product-owner` first. It answers from the record when it can cite a
  decision, and only escalates what is genuinely new. **Do not guess a default.**
- A device-visible claim → `device-verifier`. Do not assert a UI result you have not seen.
- A defect whose cause is not obvious → `evidence-debugger`. Measure before fixing.
- Before closing anything non-trivial → `self-review`.

Or run `/deliver` with a sub-batch to get dev → PO → QA per task in parallel.

## Parking, precisely

A parked task must be **recoverable by someone else**. Leave the worktree in place, and record:
the task id, the branch, what was done, what blocked it, and the exact next step. If it is blocked
on a decision, phrase it as **2–3 options with the trade-off** so the owner can answer in one line
— that is how every accepted decision here was actually made.

**Promote anything deferred to a task with `status: To Do`, never a draft.** A draft linked from a
closed task is invisible in every normal view; that is how work has been lost here before.

## The final report

Only now do you stop. Report:

- **Landed** — per task, with its closing status and the proof.
- **Parked** — per task, where it is, why, and the next step.
- **Needs the owner** — decisions as options; `In Review` items with what specifically to look at.
- **Not reached** — say so plainly. This is expected and fine.

Never claim a verify pass you did not see, and never report a device result you did not observe.
