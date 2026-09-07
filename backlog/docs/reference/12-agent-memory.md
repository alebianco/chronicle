# Agent memory and the learning loop

Memory lives in **two stores**, split by whether it is safe to share:

| Store | Contents | Committed? |
|---|---|---|
| `backlog/memory/` | 24 durable, shareable lessons — traps, methods, corrections | **Yes** — survives a new machine, reaches CI and any clone |
| `~/.claude/projects/<project>/memory/` | Machine and household facts only: the tablet's address, the Plex server, the owner's phone | No — per-machine, curatable with `/memory` |

Claude Code's auto-memory writes into the *local* store automatically; that is where a new memory
appears first. Promote it to `backlog/memory/` when it proves durable and general.

**`./check-memory-safe.sh` is what makes the repo store safe**, and it is **stage 1 of
`verify.sh`** so it cannot be bypassed. It refuses private LAN addresses, device serials, absolute
home paths, credential-shaped strings, `*.plex.direct` hostnames and email addresses. The audit
that motivated the split found exactly one unsafe memory of 24 — it carried the tablet's LAN
address, the server's name and a personal phone serial — and that one stayed local.

So the "learn iteratively" loop exists. What it lacked, and what this page is about, is **quality
control**.

## Why there is no automatic memory *writer*

The obvious idea is a `Stop` or `SessionEnd` hook that summarises the session and appends what it
learned. We deliberately do not do that. The reason is a worked example from this project:

`chronicle-coverage-gate-accumulates` asserted, in confident and well-written prose, that
within-tolerance coverage dips accumulate silently in the per-package ratchet. **They do not.**
`compare-package-coverage.py` keeps `max(baseline, current)` for every package, and it carries a
self-test that runs on *every* ratchet invocation asserting exactly that — *"a dip inside tolerance
passes"* and *"...and keeps the higher floor"*.

The memory was written from a **real symptom** (a coverage number that would not reproduce) with
the **wrong mechanism** attached. The real cause was a stale `UP-TO-DATE` JaCoCo report — the same
trap as `--rerun-tasks`. The consequence was cu-204, a task filed to fix a defect that does not
exist. And cu-135 had already been filed and closed for the same wrong reason once before.

An automatic writer would have produced that memory. It had everything such a writer keys on: a
surprising observation, a confident diagnosis, a concrete file. Nothing in a summarisation step
would have caught that the mechanism was wrong, because catching it required *reading the gate's
source and running its self-test*.

**Writing a memory is a judgement, not a summary.** It stays with auto-memory (which the owner can
curate via `/memory`) and with deliberate action.

## What the hook does instead

`.claude/hooks/memory-hygiene.sh` runs on `SessionStart` (`startup|resume`) and injects a short,
**capped** reminder — at most 16 lines, because SessionStart stdout lands in every session's
context and unbounded output is the bloat it exists to prevent. It reports:

- **Memories flagged `CORRECTED` / `superseded` / `unverified` / `was wrong`** in their
  description, so a correction is not re-forgotten. This is the mechanism that keeps the coverage
  memory from misleading a future session.
- **Index drift in both stores, both directions**: a memory file missing from its index (it will
  not be found) and an index line pointing at a deleted file.
- **An unsafe file sitting in `backlog/memory/`**, so it is caught before commit rather than at
  the gate.
- **The standing rule** — a memory states what was true when written; verify before acting on one
  that names a file, flag or mechanism.

It never parses the session transcript. That format is **internal to Claude Code and documented as
changing between versions**, so a hook that parses it is a hook that breaks on an upgrade.

Every check here is sabotage-verified, including that `verify.sh` genuinely fails on a memory
carrying a LAN address or a token.

## Correcting a memory

When you find a memory that is wrong, do not delete it silently — a deleted memory is one whose
wrong claim can be independently rediscovered and written again, which is how the coverage claim
survived two rounds.

Instead:

1. **Rewrite it as a correction.** Lead the description with `CORRECTED <date>` and state the true
   fact. Keep a short section on *what was real in the original observation*, since the symptom
   usually was real even when the mechanism was not.
2. **Say what it cost** — the task filed on a false premise, the time spent. That is what stops a
   third round.
3. **Update the index pointer line** (`backlog/memory/README.md`, or local `MEMORY.md`), or the
   index still advertises the old claim.
4. **Link the related memory** with `[[name]]`.

## Where knowledge should live

| Kind | Home | Why |
|---|---|---|
| A trap that recurs while coding | `.claude/skills/<domain>/SKILL.md` | Loads only for matching work; travels with the repo |
| A rule a test can enforce | A build gate + its KDoc | Cannot rot — see [`09-enforced-rules.md`](09-enforced-rules.md) |
| A durable architectural choice | `backlog/decisions/` ADR | Reviewable, permanent |
| A durable lesson, method or correction | `backlog/memory/` | Committed and shared; gated by `check-memory-safe.sh` |
| A machine or household fact, a session-scoped observation | Local auto-memory | Per-machine, private, never committed |

**Promotion is the normal path.** A memory starts local (auto-memory writes it there), and moves
to `backlog/memory/` once it proves durable and general — run `./check-memory-safe.sh <file>` on it
first, add it to `backlog/memory/README.md`, and delete the local copy so the two cannot drift.

**Generalise rather than exclude** where you can: "the tablet" is shareable, its IP address is not.
A memory that cannot be written without a private detail stays local.

## When a memory should become a decision

Sometimes the right destination is not `backlog/memory/` at all. A memory records **what is true**;
a decision record states **what we chose, and why, and what it costs**. When a memory is really the
second thing wearing the first thing's clothes, promote it further.

This has already happened once correctly — `chronicle-compose-adopted` cites decision-22 — and once
incorrectly: `chronicle-branch-base` opens with *"Owner decision, 2026-08-30"* and lived only in
local memory, invisible to the repo, for as long as it existed. A decision that only one machine
knows about is not a decision; it is a habit.

**Promote a memory to a decision record when all of these hold:**

- It states a **choice among alternatives**, not an observation. "Room invalidates per table" is a
  fact and stays a memory. "We branch off `feature/agentic-dev` until a release is cut" is a
  choice — something else was possible.
- **A future agent would cite it to justify an action.** That is the working test. If the answer to
  "why is it done this way?" is this memory, it belongs where decisions are looked up.
- **It outlives the situation that produced it.** A trap discovered while debugging is a memory
  even if it is permanent; a rule adopted *going forward* is a decision.
- **It has consequences worth writing down** — what it costs, what it forecloses. A decision record
  has a Context → Decision → Consequences shape; if there is nothing to put under Consequences, it
  is probably a memory.

**Do not promote:**

- A **trap or gotcha**, however hard-won — that is a skill entry or a memory. `.claude/skills/`
  loads it exactly when it is relevant, which a decision record does not.
- A **method** ("profile, don't read"; "sabotage-verify every guard"). Those belong in the
  constitution's testing section or a skill.
- Anything a **build gate already enforces** — the gate plus its KDoc *is* the record, and it
  cannot rot. See [`09-enforced-rules.md`](09-enforced-rules.md).
- A **product decision** — those are **owner-only** (D1–D14). An agent that finds one sitting in a
  memory should surface it for the owner to record, never write it itself. Agents may add
  **technical** ADRs.

**How to promote:** write the ADR (Context → Decision → Consequences), then **replace the memory's
body with a pointer** to it rather than deleting the memory — a deleted memory is a claim that can
be independently rediscovered and rewritten, which is exactly how the coverage claim survived two
rounds. `chronicle-compose-adopted` is the model: it keeps the two gotchas that are genuinely
memory-shaped and cites decision-22 for the choice itself.

The `product-owner` agent applies the same test from the other direction: if answering a question
would *set* the precedent rather than look one up, it is a decision and escalates.
