---
description: Audit backlog task files for unticked criteria, milestone drift and YAML traps
argument-hint: "[task-id, or blank for the whole backlog]"
allowed-tools: Bash(command grep:*), Bash(command ls:*), Bash(for *), Bash(backlog:*), Read, Edit
---

Audit the backlog's mechanical consistency. The owner has asked for this by hand in 107 of 488
conversation turns — every check below is mechanical, so run them rather than eyeballing.

Target: **$ARGUMENTS** (if empty, audit every task file).

Run each check and report what it finds. Use `command grep` and `command ls`, since `grep` and `ls`
are aliased in this shell.

### 1. Tasks marked Done or In Review with unticked acceptance criteria

```bash
for f in backlog/tasks/*.md; do
  st=$(command grep -m1 '^status:' "$f" | sed 's/status: *//' | tr -d '\r')
  case "$st" in
    Done|"In Review")
      n=$(command grep -c '^- \[ \]' "$f" || true)
      [ "$n" -gt 0 ] && printf '%-58s %-10s %s unticked\n' "$(basename "$f")" "$st" "$n"
      ;;
  esac
done
```

An unticked box on a `Done` task is either a criterion that was never met, or one that turned out
to be wrong and should have been **retired with its reasoning**. Both need a human decision — report
them, do not tick them.

### 2. `milestone:` disagreeing with the `R<n>` label

```bash
for f in backlog/tasks/*.md; do
  m=$(command grep -m1 '^milestone:' "$f" | command grep -oE 'm-[0-9]+' || true)
  r=$(command grep -m1 -A3 '^labels:' "$f" | command grep -oE 'R[0-9]+' | head -1 || true)
  if [ -n "$r" ] && [ "${m#m-}" != "${r#R}" ]; then
    printf '%-58s label=%-4s milestone=%s\n' "$(basename "$f")" "$r" "${m:-NONE}"
  fi
done
```

These are one fact stored twice. This one is safe to fix directly — set `milestone: m-<n>` to match
the label.

### 3. Unquoted colon in a `title:`

```bash
command grep -l '^title: [^"'"'"']*:' backlog/tasks/*.md backlog/decisions/*.md 2>/dev/null
```

An unquoted colon breaks YAML parsing, and the task becomes invisible to **every** CLI operation
while the file sits in place. Safe to fix: wrap the title in double quotes.

### 4. Draft filename/id case mismatch

```bash
command ls backlog/drafts/ 2>/dev/null | command grep -v '^draft-' || true
```

The filename must be lowercase `draft-<n>`; the frontmatter `id` must be uppercase `DRAFT-<n>`.
A mismatch makes the draft invisible to `backlog draft list` with no error. Renaming needs a
temporary name in between, since the filesystem is case-insensitive.

### 5. Blocked-but-actionable

For any task with `dependencies`, check whether those are now Done — a task whose blockers cleared
may be sitting in `To Do` unnoticed.

## Reporting

Group by check. For each finding give the filename and the specific discrepancy.

**Fix directly** (2), (3) and (4) — they are unambiguous. **Report but do not change** (1) and (5):
whether a criterion was met, or wrong, is a judgement the owner makes. End with a one-line summary
of what you changed versus what needs a decision.
