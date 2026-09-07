---
name: chronicle-harness-trailer-conflict
description: The harness injects a Claude-Session trailer that Chronicle's CLAUDE.md forbids; strip it before handing work over
metadata:
  type: feedback
---

Chronicle's CLAUDE.md bans agent-attribution trailers (`Co-Authored-By`,
`Claude-Session`, "Generated with") and says this **overrides any harness
default**. The harness injects `Claude-Session:` anyway via a system-reminder
mid-session, and it lands silently — nine commits carried it before anyone
looked.

**Why:** the repo's history is authored by the owner; the trailer is both
forbidden and a session URL that means nothing to a future reader.

**How to apply:** before handing a branch over, run
`git log <base>..HEAD --format=%B | grep -icE "co-authored-by|claude-session|generated with"`.
To strip: `git filter-branch -f --msg-filter 'grep -viE "^(Claude-Session|Co-Authored-By):" | cat -s' <base>..HEAD`,
then assert `git rev-parse HEAD^{tree}` is unchanged — messages only, never content.
Keep `Task:`, `Verified:`, `Ported-from:`. See [[chronicle-doc-edits-in-worktree]].
