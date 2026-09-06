---
id: cu-178
title: Prove FragmentScenario on one screen
status: In Progress
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

- [x] `androidx.fragment:fragment-testing` added to the version catalog as `debugImplementation`
      (it needs an empty activity in the debug manifest)
- [ ] One `CollectionsFragment` scenario test runs **on the JVM** under Robolectric, not on a device
- [x] The test-factory approach is documented in the test's KDoc so the next screen can copy it
- [ ] `features/collections` coverage rises measurably
- [ ] `./verify.sh` stays green and the unit-test stage stays under a minute — if Robolectric
      Fragment tests make it materially slower, record the measurement and reconsider
- [ ] A note in the analysis doc recording whether the approach generalises or should stop at one

## Attempt notes (2026-09-06) — half the blocker removed, half remains

**The library is not the problem. The app's host-type coupling is**, and there are *two* layers of
it, not one.

### Layer 1 — DI. Solved.

Every Fragment injected itself with `(activity as MainActivity).activityComponent!!`, naming a
concrete Activity, so `FragmentScenario`'s `EmptyFragmentActivity` failed with
`ClassCastException` in `onAttach` before a line of the screen ran. That is dependency inversion,
not Android: the Fragment depended on its host's *type* when it needed a capability.

`ActivityComponentHost` (in `injection/components/`) is that capability. `MainActivity` implements
it, so production is unchanged — **verified on the tablet, all three tabs navigate with no
exception in the app's package**. `CollectionsFragment` now injects through `injectFromHost`, and
`CollectionsFragmentScenarioTest` proves it attaches in a generic host, which it could not do
before.

### Layer 2 — AppCompat. Not solved, and not invertible.

The failure moved to `onCreateView`:

```
ClassCastException: EmptyFragmentActivity cannot be cast to AppCompatActivity
  at CollectionsFragment.onCreateView   // (activity as AppCompatActivity).setSupportActionBar
```

**Six of the ten fragments call `setSupportActionBar`.** Unlike the DI cast this cannot be hidden
behind an interface — it is AppCompat's own API and the host genuinely must be an
`AppCompatActivity`. And `FragmentScenario` offers **no overload accepting a host class**: verified
against `fragment-testing` 1.8.9, whose four `launch`/`launchInContainer` signatures take only a
fragment class, args, a theme and a factory.

### What the remaining route costs

A **debug-manifest `AppCompatActivity` host** plus `ActivityScenario` driving fragment transactions
directly, instead of `FragmentScenario`. That means a new `src/debug/AndroidManifest.xml` and a
host activity shipped in the debug build — a larger, more visible change than a proof of concept
should make unattended, and it touches what ships to the device.

Returned to `To Do` with the groundwork done: the DI seam is in place and permanent, so whoever
picks this up starts at layer 2. The estimate should assume the manifest route, not the library.

### Also usable now

`testActivityComponent` gives *any* test a way to hand a Fragment its dependencies. That is worth
having even without scenarios — it is the seam a `LibraryFragment` test would need too.
