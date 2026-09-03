---
id: cu-134
title: Guard against logging whole model collections
status: To Do
assignee: []
created_date: '2026-09-03'
updated_date: '2026-09-03'
labels:
  - R2
  - performance
  - agentic
milestone: m-2
dependencies:
  - cu-110
priority: medium
---

## Description

[[cu-110]] suggested this and the evidence now justifies it: interpolating a model collection into
a `Timber` call is a **recurrent** defect in this codebase, and hand-hunting keeps missing
instances.

Known history:
- cu-110 fixed three (`HomeViewModel`, `ChapterListAdapter`, `ChapterRepository`) and stated the
  class had been swept.
- The 2026-09-02 review then found **three more**, including
  `TrackRepository.getBookIdForTrack` doing `Timber.i("Track is $track")` — a full
  `MediaItemTrack.toString()` **on the 1 Hz progress path**. Fixed under cu-110.

The cost is real because `Audiobook.toString()` drags in the serialized `chapters` column: one
measured session produced **3.38 MB of logging across 2920 lines**, built and written on the main
thread.

`TokenLoggingTest` already proves the shape works — a static scan over `Timber` call sites that
fails the build. This is the same idea for *size* rather than secrecy.

## Acceptance Criteria

- [ ] A test fails the build when a `Timber` call interpolates a bare collection of a model type
      (`List<Audiobook>`, `List<MediaItemTrack>`, `List<Chapter>`, `Collection<...>`)
- [ ] `.map { it.id }`, `.size`, and `.count()` are permitted — the point is to force a projection,
      not to ban logging
- [ ] Follows `TokenLoggingTest`'s structure, including its two self-guards: a file-count floor and
      a known-bad matcher case, so the scan cannot silently become vacuous
- [ ] **Verified to fail** before being trusted (m-0 rule): reintroduce one of the six historical
      instances and confirm the build breaks
- [ ] Existing violations are all fixed rather than baselined — there should be none left to
      grandfather
