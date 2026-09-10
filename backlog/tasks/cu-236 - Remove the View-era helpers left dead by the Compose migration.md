---
id: cu-236
title: Remove the View-era helpers left dead by the Compose migration
status: Done
assignee: []
created_date: '2026-09-09'
updated_date: '2026-09-10 06:59'
labels:
  - R3
  - debt
milestone: m-2
dependencies: []
priority: low
ordinal: 99000
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

- [x] **Four files, not one.** The same pattern held three more times: `util/ImageViewExt.kt`
      (`setImageResourceIfChanged`), `util/TextViewExt.kt` (`setTextIfChanged`) and
      `views/HorizontalChildReadySwipeRefreshLayout.kt` were all dead in production too, each kept
      alive by its own test. All four removed, with their three tests
- [x] **Re-confirmed before deleting**: no `@BindingAdapter` annotation anywhere, and zero
      non-comment references from `main`/`debug`/`release`. Every apparent "caller" was a comment
      saying Compose had replaced it — `CoverImage.kt` states it outright
- [x] Two orphaned view-tag ids removed from `res/values/ids.xml` (`tag_bound_image_src`,
      `tag_thumb_aspect_ratio`); the third went with `ImageViewExt`. The file is kept with a note,
      since `ids.xml` is where such an id belongs if one is needed again
- [x] `ObsoleteSdkInt` baseline entries **15 → 4**, and the whole lint baseline **546 → 338**
- [x] Coverage baseline lowered deliberately, 57.78% → 57.75% — deleting well-tested dead code is
      exactly the case the ratchet's own message describes
- [x] `./verify.sh` green, 10 stages

## Closing notes, 2026-09-09

Landed together with the dead-`SDK_INT`-guard cleanup, since both are the same finding from two
angles: code that minSdk 27 and the Compose migration between them made unreachable.

The near-miss worth recording: the first grep for `bindImageRounded` returned **8 call sites**, which
reads as "live". They were all in its own test. A count is not a caller — the follow-up that checked
*where* those references were is what turned this from "leave it alone" into a deletion.

## Notes

Closing status **Done** if the greps confirm it: this is a mechanical removal a machine can prove,
with no product judgement in it.

Found while gathering evidence for the minSdk evaluation, not as part of it.
