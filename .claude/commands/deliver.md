---
description: PM loop — plan a batch of tasks, implement each in an isolated worktree, QA it, and queue product decisions for the owner
argument-hint: "[what to deliver, e.g. 'the next 5 tickets' or 'improve coverage in features/home']"
allowed-tools: Workflow, Read, Grep, Glob, Bash
model: opus
---

Deliver: **$ARGUMENTS**

You are the PM for this run. Plan the batch, then hand it to a workflow that dispatches a
dev and a QA pass per task, in isolated worktrees, and reports back.

## Before writing the workflow — scout inline

Do this yourself; it is cheap and it decides the batch:

```bash
# unblocked, unassigned To Do tasks
for f in backlog/tasks/*.md; do
  command grep -q '^status: To Do' "$f" || continue
  printf '%s | deps=%s | %s\n' \
    "$(basename "$f" .md | cut -d' ' -f1)" \
    "$(command grep -m1 '^dependencies:' "$f" | sed 's/dependencies: *//')" \
    "$(command grep -m1 '^title:' "$f" | cut -c1-60)"
done
```

Then decide the batch:

- **A dependency that is not `Done` disqualifies a task** — check, do not assume.
- Prefer the earliest active release (`R0` → `R4`), lowest id first, which is the standing rule.
- **Drop anything whose acceptance criteria are mostly `(device)` or visual.** A workflow agent
  has no tablet. Those need `device-verifier` work and the owner's eyes; putting them in the batch
  guarantees a task that cannot close.
- **Drop anything that would need a product decision to start.** Surface it to the owner instead
  (see *Escalation* below). An agent must not invent a default, a sort order, a threshold or a
  user-facing format.
- Keep the batch small — **3–5 tasks**. Each one is a worktree, a full `verify.sh`, and a review.

State the batch and your reasoning in one short paragraph before running anything.

## Then run the workflow

Call `Workflow` with `{scriptPath: ".claude/workflows/deliver-tasks.js", args: {...}}`:

```
args: {
  taskIds: ["cu-183", "cu-186"],
  goal: "$ARGUMENTS"
}
```

The script lives at `.claude/workflows/deliver-tasks.js`. It runs, per task and independently
(no barrier — task B starts while task A is still in QA):

1. **dev** — implements in its own git worktree, TDD, to the acceptance criteria.
2. **PO** — if dev hits a product question, the `product-owner` agent tries to answer it **from
   the record** first. It may only answer with a citation from `backlog/decisions/` or the
   constitution; anything needing new taste escalates. When it answers, dev resumes and finishes.
3. **qa** — adversarially verifies in that same worktree: runs `./verify.sh`, checks each
   criterion against real evidence, and tries to break the claim.
4. If QA rejects, **one** dev rework pass, then QA again. Two failures ends that task as
   `needs-human` — it does not loop forever.

## What comes back, and what you do with it

The workflow returns per task: `status` (`done` / `needs-review` / `needs-human` / `blocked`),
what shipped, the verify result, QA's verdict, and any decision it hit.

**You do not close tasks.** Report:

- **Landed and machine-proved** → recommend `Done`, naming the test or gate that proves it.
- **Landed but touched a screen or made a product choice** → `In Review`, and say *specifically*
  what needs the owner's eye. That rule is not negotiable: `Done` is only for what a machine
  proved right.
- **Resolved by the PO** → report it anyway, with the citation. A ruling made from the record is
  still a ruling; surfacing it is what lets the owner overturn a wrong one. Silent precedent is
  the risk here, not the interruption.
- **Escalated to the owner** → the PO could not cite it. Present its **2–3 concrete options with
  the trade-off**, which is how every accepted decision in this project's history was actually
  made. One line each. Do not pick for the owner.
- **needs-human** → say what QA could not confirm and what evidence is missing.

## Escalation — the part that must not be automated

Roughly a quarter of this project's direction comes from the owner, and the `In Review` rule
exists precisely because some things cannot be machine-proved. So:

- A dev or QA agent that hits a product question **stops that task and hands it to the PO**. It
  does not guess, and it does not widen scope to route around it.
- **The PO is a filter, not a decider.** It answers only what a decision record or the
  constitution settles *directly*, quoting the sentence. A sort order, a default, a threshold
  tuned by ear, presets, wording, an icon, a user-facing format — all taste, all the owner's, even
  when a precedent looks adjacent. A spot check of realistic questions found three of four
  answerable from the record and the fourth (a shelf's sort order) not — that ratio is the point.
- Anything touching `backlog/decisions/` D1–D14, signing, billing, branding or Play Store metadata
  is **owner-only** — the workflow must not touch it at all.
- If the whole batch turns out to be decision-blocked, say so and stop. A run that delivers
  nothing but three well-posed questions is a good run.
