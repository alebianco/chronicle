---
id: cu-196
title: Scope or drop kotlin-result
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - debt
dependencies: []
priority: low
milestone: m-2
ordinal: 64000
---

## Description

The R2 review guide raised this as one of *"two things worth your decision, not filed"* — and it
stayed unfiled. `backlog/docs/reference/R2-REVIEW-GUIDE.md`:

> **kotlin-result is barely earning its place**: 7 imports across 5 files, with `Ok`/`Err`
> constructed in exactly one, while ~6 hand-rolled sealed outcome types coexist elsewhere. Either
> adopt it more widely or scope it deliberately.

This is deliberately **not** part of cu-194. That task decides which *new* libraries to pick;
this is an existing dependency whose reach is inconsistent — a different question, and a smaller
one.

## The actual decision

Three outcomes, and the middle one is probably right:

1. **Adopt it more widely** — replace the hand-rolled sealed outcome types with `Result<V, E>`.
   Consistency, at the cost of touching error handling across the codebase.
2. **Scope it deliberately** — write down where it is used and why, and leave the sealed types
   alone where they carry domain-specific cases a generic `Err` would flatten. Cheapest, and
   honest.
3. **Drop it** — replace the 7 imports with a sealed type or `kotlin.Result`, and remove the
   dependency.

Note the confusion risk that makes *some* answer worthwhile: `kotlin.Result` (stdlib, `success`/
`failure`) and `com.michael-bull.kotlin-result` (`Ok`/`Err`) coexist here, and the reference docs
already contained a code sample mixing them into a `when` on nonexistent `Result.Success` /
`Result.Failure` subtypes — corrected 2026-09-06, but it is the kind of mistake this ambiguity
invites.

## Acceptance Criteria

- [ ] The 7 import sites and the ~6 hand-rolled outcome types are enumerated, not estimated
- [ ] One of the three outcomes chosen and recorded, with reasoning
- [ ] If scoping: the rule for when to use which is written where an agent will find it
      (`CLAUDE.md` conventions, most likely)
- [ ] If dropping: the dependency is removed from the catalogue and `app/build.gradle.kts`
- [ ] `./verify.sh` green
