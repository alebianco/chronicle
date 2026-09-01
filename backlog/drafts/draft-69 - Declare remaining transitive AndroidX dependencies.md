---
id: DRAFT-69
title: Declare the remaining transitive AndroidX dependencies
status: Draft
assignee: []
created_date: '2026-08-31'
labels: [R2, hygiene]
dependencies: []
priority: low
milestone: m-2
---

> **Draft id note.** Filed as `DRAFT-69` so the Backlog.md drafts view can see it —
> the tool keys drafts on the `DRAFT-` id prefix, not the directory or the status field.
> On promotion it becomes a `cu-` task again. Existing references to **cu-69** mean this file.

## Description

Tail of the declared-vs-used audit from the R0-close review. The high-risk cases
(`androidx.media`, `kotlin-reflect`) were fixed in cu-65; these remain undeclared but arrive from
appcompat/material, which are much less likely to drop them:

| Package | Imports / files | Arrives via |
|---|---|---|
| `androidx.core.*` | 45 / 29 | appcompat, material → core 1.16.0 |
| `androidx.recyclerview` | 40 / 16 | material → 1.3.0 |
| `androidx.fragment` | 13 / 13 | appcompat → 1.5.4 |
| `androidx.constraintlayout` | 5 / 2 | material → 2.2.1 |
| `androidx.transition`, `coordinatorlayout`, `interpolator` | 1 each | material |
| `androidx.sqlite` | 2 / 2 | room-runtime |

Note `androidx.fragment` resolving to **1.5.4** — old, and directly relevant given every screen is a
Fragment.

Three instances of transitive-only breakage have now occurred (cu-60 lifecycle, cu-65
localbroadcastmanager, cu-65 androidx.media). The pattern is established enough to close out rather
than wait for a fourth.

Worth doing alongside: a check that the app's declared set stays a superset of what it imports, so this
does not need re-auditing by hand.

## Acceptance Criteria

- [ ] Every package imported by `app/src/main` is declared, or its transitive source is documented
- [ ] `androidx.fragment` pinned deliberately rather than inherited at 1.5.4
- [ ] `./verify.sh` and `./test_release_build.sh` green
