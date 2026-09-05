---
id: cu-23
title: Android Auto quality pass
status: In Review
assignee: ['@claude']
created_date: '2026-07-13'
labels:
  - R2
  - comfort
milestone: m-2
dependencies: []
priority: medium
ordinal: 27000
---

## Description

Presence, metadata incl. narrator/series, refresh (fabiogermann patterns; #106/#99/#104). Owner-approved 2026-07-05.

## Acceptance Criteria

- [x] App reliably appears in Auto — the system enumerates it as media **Service #0,
      `isDefault=true`**, it serves a non-empty browse root, and all four categories resolve.
      Covered by `AutoBrowseTreeTest`, which runs on API 27, API 35 **and** an Automotive image.
- [ ] **Book+chapter metadata correct on a real head unit** — the *shape* is now pinned (a served
      book is playable, titled and has an author subtitle), but whether it *reads* correctly in a
      car is a visual check no emulator settles. Left for the owner.

## Implementation Notes

Closed as far as a machine can take it. An **Android Automotive emulator** now exists (see the
`chronicle-auto-emulator` memory and cu-89), which is what made this reachable at all.

### What was built

`AutoBrowseTreeTest` — an instrumented test that binds a real `MediaBrowserCompat` and walks the
tree. This is the only way to exercise `onGetRoot`/`onLoadChildren` as Auto calls them: both need a
bound service, a real `Result` to detach and send on, and a caller package to validate, so a unit
test cannot reach them. It runs on **API 27, API 35 and the Automotive image** — 10 tests on the
managed devices, 7 on Auto.

### It found a real crash on its first run

`MediaPlayerService.onDestroy` called `mediaController.metadata.id`. `getMetadata()` is
`@Nullable`, but Kotlin sees the compat signature as platform-typed, so `.id` compiled and threw
`NullPointerException: getMetadata(...) must not be null` — **crashing the whole process** whenever
a client bound the service and released it without playing anything. Which is exactly what Android
Auto does when it browses. Fixed with `?.`, and the KDoc records why the type system did not help.

### Two traps worth recording

1. **`MediaBrowserCompat` must be built and driven on the main thread.** Its constructor creates a
   `Handler`, so building it on the instrumentation thread throws *"Can't create handler inside
   thread ... that has not called Looper.prepare()"* — and a `subscribe` from there would deliver
   to a looper that never runs. Every browser call is posted to the main looper and awaited through
   a latch.
2. **The first version tested provisioning, not the browse tree.** It asserted the library browses
   non-empty, which passed on the Auto emulator (used, synced) and failed on a freshly-provisioned
   api35 (mock mode seeds the *login*, not a *refresh*). Split into an unconditional "the category
   resolves and completes" and a conditional "any book served carries renderable metadata".

### Sabotage verification

Renaming a category's id **did not fail** the first version — the assertion compared the served
tree against the same enum, so both sides moved together. The literal ids are pinned separately by
`AutoBrowseCategoryTest` (cu-99), so the coverage existed, but the KDoc claimed more than the test
proved. Corrected, and an assertion added that the id is never the localized title — which
**does** catch cu-99's original bug when reintroduced, and which no unit test can.

### Why In Review

Criterion 1 is machine-proven. Criterion 2 is half-proven: a served book is playable, titled and
carries an author subtitle, but whether the metadata *reads* correctly on a real head unit — the
chapter title in particular — is a visual check. cu-165 (Auto seek bar spans the track, not the
chapter) is In Review for a related reason and is worth looking at in the same sitting.
