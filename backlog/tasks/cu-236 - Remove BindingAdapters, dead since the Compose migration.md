---
id: cu-236
title: "Remove BindingAdapters, dead since the Compose migration"
status: To Do
assignee: []
created_date: '2026-09-09'
labels:
  - R3
  - debt
milestone: m-3
dependencies: []
priority: low
---

## Description

`views/BindingAdapters.kt` holds one function, `bindImageRounded`, and **nothing in production calls
it**. It is kept alive entirely by its own test.

Measured 2026-09-09:

- **8 call sites**, all in `views/BindImageRoundedTest.kt`
- **2 mentions in main source**, both *comments*: `MainActivityViewModel.kt:248` cites it in a note
  about rebinding cost, and `views/compose/CoverImage.kt:27` says outright *"The Compose migration
  replaced `bindImageRounded`"*
- **No `@BindingAdapter` annotation** anywhere in the file — the DataBinding machinery it was named
  for is long gone

So the Compose migration replaced it, said so in a comment, and left the implementation behind.

## Why it is worth a ticket rather than a silent delete

Same shape as cu-233 (`kotlin-parcelize`, which "applied to something nothing uses") and cu-228
(three dependency declarations that resolved transitively anyway): the value is in what stops being
carried, not in bytes saved.

It also carries one `ObsoleteSdkInt` lint finding in the baseline, so removing it shrinks that
baseline by one without any judgement call.

The test is the interesting part. `BindImageRoundedTest` is a **real test of dead code** — it passes,
it is not flaky, and it proves nothing about the shipped app. It goes with the implementation.

## Acceptance Criteria

- [ ] `views/BindingAdapters.kt` and `views/BindImageRoundedTest.kt` removed
- [ ] **Re-confirmed first**: no `@BindingAdapter` annotation, no non-comment reference from
      `app/src/main`, `app/src/debug` or `app/src/release`. If a caller exists, stop — that inverts
      the finding
- [ ] The two comments that cite it updated, since they will name a file that no longer exists
- [ ] Its `ObsoleteSdkInt` entry dropped from `app/lint-baseline.xml`
- [ ] Coverage baseline updated if the ratchet moves — deleting well-tested dead code lowers the
      percentage for a good reason, which is exactly the case the ratchet's own message describes
- [ ] `./verify.sh` green, 10 stages

## Notes

Closing status **Done** if the greps confirm it: this is a mechanical removal a machine can prove,
with no product judgement in it.

Found while gathering evidence for the minSdk evaluation, not as part of it.
