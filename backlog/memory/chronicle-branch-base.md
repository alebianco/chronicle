---
name: chronicle-branch-base
description: Chronicle task branches base off feature/agentic-dev, not develop, until a release is cut — recorded as decision-23
metadata:
  type: project
---

**The choice itself is now [[decision-23]]** — read that for the reasoning, the consequences, and
the note that it expires when a release is cut. This memory was promoted because it opened with
"Owner decision, 2026-08-30" while living only on one machine, which is the definition of a
decision nobody can look up.

What stays here, because it is a fact rather than a choice:

```bash
git worktree add -b task-<id>-<slug> .worktree/task-<id>-<slug> feature/agentic-dev
```

`develop` lacks the entire `backlog/` tree, so a worktree cut from it has **no task files to work
from**. And `local.properties` is gitignored — a new worktree without it fails every Gradle task
with "SDK location not found", which reads like a broken build rather than missing setup.
`./setup-repo.sh` handles both that and the pre-commit hook.

See [[chronicle-doc-edits-in-worktree]].
