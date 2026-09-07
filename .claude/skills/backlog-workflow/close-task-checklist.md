# Closing a task — the mechanical checklist

The owner has audited backlog state by hand in **107 of 488 turns** (22% of all conversation turns
across 242 sessions), repeatedly asking variants of:

> *"all tickets so far are updated? state and checklists?"*
> *"some tasks in review/done don't have all AC checked — can you verify mechanically?"*
> *"some tasks have the milestone label but not the milestone property in the frontmatter"*

Every one of those is mechanically checkable. Run this before declaring any task closed.

## The checks

```bash
# Run from the repo root. Substitute the task id.
ID=cu-187
F=$(command ls backlog/tasks/ | command grep "^task-${ID#cu-} \|^task-$ID ")
```

### 1. Every acceptance criterion is ticked, or explicitly retired

```bash
command grep -n '^- \[ \]' "backlog/tasks/$F"
```

Must return nothing. An unticked box means the task is **not** Done.

- A criterion that is a **visual or on-device check you did not perform** stays unticked *and* the
  task goes to `In Review` — never tick it on the strength of a test that cannot see what the
  criterion asks about.
- A criterion that turned out to be **wrong** rather than unmet is **retired with its reasoning**
  written in place, never silently ticked or deleted.

### 2. `milestone:` mirrors the `R<n>` label

```bash
command grep -E '^(milestone|labels):' "backlog/tasks/$F"
```

`milestone: m-<n>` and the `R<n>` label are **one fact stored twice**, in the two places the
Backlog.md CLI reads it. 31 files had drifted to label-only before this was written down.

### 3. The status is right — `In Review` vs `Done`

**The question is not "feature or bug", it is "can a machine prove this was right?"**

`Done` only when the proof is automated — a test, a build gate, a measurement a script reproduces.

`In Review` when:
- it **changed a screen** (layout, wording, an icon, what a state looks like);
- it made a **product or design choice** the owner might want differently (a sort order, a default,
  a threshold tuned by ear, a set of presets, a user-facing file format);
- an acceptance criterion is a **visual/on-device check that was not performed**.

When setting `In Review`, write in the task file **what specifically needs the owner's eye** — "the
shelf's sort order", not "please review".

### 4. `## Implementation Plan` has become `## Implementation Notes`

```bash
command grep -n '^## Implementation' "backlog/tasks/$F"
```

The plan is replaced or condensed into notes: what actually changed, decisions taken, follow-ups.

### 5. Unfinished items were promoted, not stranded

**Deferred work is not a draft.** A draft is an idea nobody committed to; work that was started,
scoped and postponed is a **task** with `status: To Do`.

A deferred item filed as a draft and linked from a **closed** task is invisible in `backlog board`
and `backlog task list` — which is exactly how cu-73 and cu-132 items got lost.

**List in the closing notes where each item went.**

### 6. Assignee and dependencies are consistent

```bash
command grep -E '^(assignee|dependencies|status):' "backlog/tasks/$F"
```

If this task was a dependency of others, check whether they are now unblocked.

## Repo-wide audit

To answer the owner's recurring question directly, across all tasks:

```bash
# Tasks marked Done or In Review that still carry an unticked criterion
for f in backlog/tasks/*.md; do
  st=$(command grep -m1 '^status:' "$f" | sed 's/status: *//')
  case "$st" in
    Done|"In Review")
      n=$(command grep -c '^- \[ \]' "$f" || true)
      [ "$n" -gt 0 ] && printf '%-58s %-10s %s unticked\n' "$(basename "$f")" "$st" "$n"
      ;;
  esac
done

# Tasks whose milestone and R<n> label disagree
for f in backlog/tasks/*.md; do
  m=$(command grep -m1 '^milestone:' "$f" | command grep -oE 'm-[0-9]+' || true)
  r=$(command grep -m1 '^labels:' "$f" | command grep -oE 'R[0-9]+' || true)
  [ -n "$r" ] && [ "${m#m-}" != "${r#R}" ] && printf '%-58s label=%s milestone=%s\n' "$(basename "$f")" "${r:-none}" "${m:-none}"
done

# Frontmatter with an unquoted colon in the title (invisible to every CLI operation)
command grep -l '^title: [^"'"'"']*:' backlog/tasks/*.md backlog/decisions/*.md 2>/dev/null
```

That last one matters: an unquoted colon breaks YAML parsing and the task becomes invisible to
`backlog task <id>` while the file sits in place. Nine files had this.
