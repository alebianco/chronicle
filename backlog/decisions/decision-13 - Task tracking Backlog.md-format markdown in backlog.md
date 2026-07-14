---
id: decision-13
title: Task tracking: Backlog.md-format markdown in backlog/
date: '2026-07-13'
status: accepted
---

## Context

D12 rule 6 (file over app) requires trackable work that isn't locked to any forge.

## Decision

One task = one markdown file (backlog/tasks/task-<id> - <Title>.md) with frontmatter + acceptance criteria; drafts in backlog/drafts/; technical ADRs and product decisions in backlog/decisions/; reference docs + specs + plans in backlog/docs/. The Backlog.md CLI (MIT) is optional convenience over the files.

## Consequences

The whole project (tasks, decisions, docs) moves to any git forge with a clone; PRODUCT_BACKLOG.md and todo.md dissolved into this structure 2026-07-13.
