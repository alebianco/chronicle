---
name: backlogmd-completed-search-gap
description: "Backlog.md does not index backlog/completed/ in `backlog search`; tracked upstream as issue 825"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 656032e3-7b86-4192-a0b4-081a8d786f81
  modified: 2026-09-03T06:39:56.119Z
---

`backlog search` does not index tasks under `backlog/completed/`, so a task retired with
`backlog task complete` becomes invisible to search while remaining reachable by id
(`backlog task cu-<n>`) and by `grep -r`.

The owner pointed to the upstream request to fix this:
<https://github.com/MrLesk/Backlog.md/issues/825>

**Why it matters here:** Chronicle retired R0 and R1 this way (2026-09-03), moving 73 tasks to
`completed/` to clear the Kanban board. That makes this gap the single accepted cost of the
approach — and a temporary one, since a fix is requested. Check whether it has landed before
proposing any workaround.

Related: [[chronicle-branch-base]]
