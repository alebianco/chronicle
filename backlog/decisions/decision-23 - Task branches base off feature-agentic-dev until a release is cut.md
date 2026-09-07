---
id: decision-23
title: "Task branches base off feature/agentic-dev until a release is cut"
status: accepted
created_date: '2026-09-06'
---

## Context

The owner made this call on **2026-08-30**, and until now it lived only in a local auto-memory
(`chronicle-branch-base`) on one machine. It was invisible to the repo, to CI, and to a fresh
clone — so a new agent or contributor would branch from `develop` and find no task files at all.

That is the failure this record exists to close: *a decision that only one machine knows about is
not a decision, it is a habit.*

The situation itself: the agentic-first restructure — the whole `backlog/` tree (tasks, decisions,
milestones, docs), `verify.sh`, the skills and hooks — lives on `feature/agentic-dev`. `develop` is
well behind it and lacks that tree entirely.

## Decision

**Branch every task worktree off `feature/agentic-dev`, not `develop`**, until a release version is
cut.

```bash
git worktree add -b task-<id>-<slug> .worktree/task-<id>-<slug> feature/agentic-dev
```

Rebasing or fast-forwarding `develop` is **deferred to release time** rather than done
incrementally.

## Consequences

- A worktree cut from `develop` has **no task files to work from** — the failure is immediate and
  confusing rather than subtle, which is the one mercy here.
- `develop` continues to diverge. The longer this holds, the larger the eventual reconciliation;
  that cost is accepted deliberately in exchange for not maintaining two trees during the
  restructure.
- **This record expires.** When a release is cut, the base returns to `develop` and this decision
  should be superseded rather than quietly ignored.
- `local.properties` is gitignored, so every new worktree needs it copied in or Gradle fails with
  *"SDK location not found"* — which reads like a broken build rather than missing setup.
  `./setup-repo.sh` handles this, along with installing the `pre-commit` hook and trusting the RTK
  filters.

## Notes

Recorded, not decided, by an agent: the choice was the owner's. This is a **technical** ADR
(repo and branch mechanics), which agents may add; product decisions D1–D14 remain owner-only.
