---
id: cu-233
title: "Remove kotlin-parcelize, which nothing uses"
status: Done
assignee: []
created_date: '2026-09-08'
labels:
  - R2
  - debt
milestone: m-2
dependencies: []
priority: low
---

## Description

**`PlexUser` is the only `Parcelable` in the codebase, and nothing ever parcels it.** No `putExtra`,
no `Bundle`, no navigation argument, and no other `: Parcelable` anywhere in `app/src/main`. It is
residue from the Fragment era that the Compose migration removed — parcelling was how a `PlexUser`
used to reach a Fragment, and Navigation Compose passes ids instead.

So the `kotlin-parcelize` plugin, the `@Parcelize` annotation, the `kotlinx.parcelize` import and the
`: Parcelable` supertype are all dead weight. Measured 2026-09-08: removing all four leaves
**`./verify.sh` green at 10 stages** on the current AGP 8.13.2.

## Why it is worth its own ticket

It surfaced while re-measuring AGP 9 for cu-214, which had recorded `kotlin-parcelize` as one of five
blockers — *"applies, silently does nothing"*. The truthful version is **"applies to something
nothing uses"**, and that reframing matters twice:

- It is a **cleanup that stands on its own**, at the current AGP. It should not wait on a toolchain
  decision it does not depend on.
- It removes one of the two remaining hard blockers from the AGP 9 question, which
  [[cu-231]] (Circuit) is gated behind. Doing it early makes that decision cheaper and clearer.

Same shape as cu-228, which removed three dependency declarations that resolved transitively anyway:
the value is in what stops being carried, not in bytes saved.

## Acceptance Criteria

- [x] `@Parcelize`, the `kotlinx.parcelize` import and `: Parcelable` removed from `PlexUser`
- [x] The `id("kotlin-parcelize")` plugin removed from `app/build.gradle.kts`
- [x] **Re-confirmed across all of `app/src/`**, `androidTest` included: no `putExtra`,
      `getParcelable` or `Bundle` mentions `PlexUser`, and it held the only `: Parcelable` in the
      tree
- [x] `./verify.sh` green, 10 stages
- [x] `./test_release_build.sh` **exit 0** — 9,402 classes in dex, 24 `@Serializable` models checked,
      all reflection-dependent classes survived R8

## Notes

Closing status **Done** if the greps confirm it is unused: this is a mechanical removal a machine can
prove, with no product judgement in it.

**If something *does* parcel it**, stop and say so — that inverts the finding, and the annotation
stays until whatever needs it is understood.
