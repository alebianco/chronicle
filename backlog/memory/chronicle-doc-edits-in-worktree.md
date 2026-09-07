---
name: chronicle-doc-edits-in-worktree
description: "Chronicle doc edits (CLAUDE.md, backlog/) must be made inside the task worktree, not the main checkout"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: eb8384f7-4315-4572-b92d-890d4987c009
  modified: 2026-09-04T15:07:47.305Z
---

When working a Chronicle task in `.worktree/task-NN-slug`, make **all** edits there — including
`CLAUDE.md` and `backlog/tasks/*`. It is easy to drift back to the main checkout for docs because
the backlog feels "global", but the repo's rule is one task = one branch = one worktree, and the
docs must land in the same commit series as the code that made them true.

Recovering from the mistake means a tagged stash and an `apply` (never a bare `git stash`/`pop` —
the stack is shared with other sessions):

```
git stash push -u -m "<tag>" -- <files>
git stash list --format='%H %gs'        # capture the SHA
cd .worktree/<task> && git stash apply <sha>
cd <main> && git stash drop stash@{n}   # re-find by tag first
```

**Why:** hit during cu-33. Confirmed other sessions' stash entries were present at the time, so a
bare pop would have taken someone else's work.

**How to apply:** before editing a doc, check the working directory is the worktree. See
[[chronicle-branch-base]].
