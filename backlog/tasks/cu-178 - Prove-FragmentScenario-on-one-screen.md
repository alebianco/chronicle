---
id: cu-178
title: Prove FragmentScenario on one screen
status: To Do
assignee: []
created_date: ''
labels:
  - R2
  - maintainability
dependencies: []
priority: high
---

## Description

**Fragments are 9,000 missed instructions — 21% of everything uncovered**, the single largest body
in the app, and eight of them carry over 450 each.

`androidx.fragment:fragment-testing` is the official tool for this and is **not in
`libs.versions.toml`**. `launchFragmentInContainer<T>()` drives the real Fragment lifecycle, and
**under Robolectric it runs on the JVM** — no emulator, so it counts toward the ratchet.

This is the largest single lever left, and it invalidates the earlier claim in the maintainability
analysis that Fragments are "unreachable by a JVM unit test". They are not; the project simply has
not adopted the tool.

## Do one screen first, not eight

The real cost is not the library — it is that these Fragments take their dependencies through a
`ViewModelProvider.Factory` from the Dagger graph, so a scenario test needs a way to inject a test
factory. That approach should be proven on **one** screen before eight are committed to.

**Start with `CollectionsFragment`**: 806 instructions, the simplest of the eight, and
`CollectionsViewModel` already has tests — so if the scenario fails, the cause is unambiguously the
new plumbing rather than the screen's logic.

If the pattern works, `HomeFragment` (909), `LibraryFragment` (1,259), `AudiobookDetailsFragment`
(1,305) and `CurrentlyPlayingFragment` (1,356) follow the same shape.

## Cost to weigh honestly

Robolectric Fragment tests are slower to write and slower to run than pure unit tests, and
Robolectric already carries a known cost here — it is why PIT cannot mutate through it (see the
`pitestDebug` allowlist). If a screen needs more scaffolding than it has behaviour, say so and stop
rather than pushing through for the metric.

## Acceptance Criteria

- [ ] `androidx.fragment:fragment-testing` added to the version catalog as `debugImplementation`
      (it needs an empty activity in the debug manifest)
- [ ] One `CollectionsFragment` scenario test runs **on the JVM** under Robolectric, not on a device
- [ ] The test-factory approach is documented in the test's KDoc so the next screen can copy it
- [ ] `features/collections` coverage rises measurably
- [ ] `./verify.sh` stays green and the unit-test stage stays under a minute — if Robolectric
      Fragment tests make it materially slower, record the measurement and reconsider
- [ ] A note in the analysis doc recording whether the approach generalises or should stop at one
