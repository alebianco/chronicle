---
id: DRAFT-168
title: 'media3-cast ships unused'
status: Draft
assignee: []
created_date: '2026-09-05'
labels:
  - R2
  - debt
priority: low
---

## Description

Found while removing commented-out Cast code from `AudiobookDetailsFragment` (2026-09-05).

`implementation(libs.media3.cast)` is declared in `app/build.gradle.kts:202`, and **nothing in
`app/src/main` imports anything from it** — no `CastPlayer`, no `CastContext`, no
`MediaRouteButton`. The only trace was 16 lines of commented-out `MediaRouteButton` wiring inherited
from upstream, referring to a `castContext` that no longer resolves. That block is now deleted.

So the app ships a Cast dependency it never uses. Two ways to resolve it, and the choice is the
owner's because it is a product question, not a cleanup:

1. **Drop the dependency** — smaller APK, fewer keep rules, honest build file. Cast becomes a
   from-scratch feature if wanted later.
2. **Implement Cast** — it is one of the most-requested features upstream
   ([#8](https://github.com/mattttvaughn/chronicle/issues/8), still open) and the dependency being
   present suggests it was intended.

A draft rather than a task: nobody has committed to either direction.

## Notes

Measure the APK delta before deciding — if `media3-cast` is largely shrunk away by R8 already, the
size argument for (1) is weak and the decision rests purely on whether Cast is wanted.
