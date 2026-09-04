---
id: cu-162
title: Upgrade androidx.fragment past 1.5.4
status: In Review
assignee: []
created_date: '2026-09-04'
labels:
  - R3
  - hygiene
dependencies:
  - cu-69
milestone: m-3
priority: low
---

## Description

Split out of [[cu-69]], which **pinned** `androidx.fragment` at 1.5.4 rather than moving it. Pinning
was the right scope for that task — it makes today's behaviour explicit — but 1.5.4 is old and every
screen in this app is a `Fragment`, so the version is more load-bearing here than the number
suggests.

Deliberately its own task because an upgrade is a behaviour change with its own testing, where
cu-69's whole point was that nothing changed.

## What to check when doing it

- **`FragmentManager` strictness.** Later versions tightened state-loss and lifecycle rules; the
  app commits from a debug hook (`--ez show_browse true`) and from `Navigator`, and cu-24 already
  recorded that a `commit()` in `onCreate` throws `FragmentManager has not been attached to a host`.
- **`viewLifecycleOwner` timing.** cu-52 put a `collectWhileStarted` on it in every fragment; the
  window in which it is valid has moved between versions.
- **`fragment-ktx` vs `fragment`.** cu-69 declared `fragment-ktx`, which is what the source uses
  (`by viewModels` is not used here, but `commit { }` is available).
- The three instrumented tests (`./verify.sh --instrumented`) are the only automated coverage of
  the Fragment layer at all, so run them — the unit suite constructs no Fragment.

## Acceptance Criteria

- [x] `androidx.fragment` moved to a current version — **1.5.4 → 1.8.9**, verified as the
      *resolved* version and not merely the requested one
- [x] `./verify.sh` green **and** `./verify.sh --instrumented` green — 3/3 on API 27 **and** API 35,
      including `survivesRecreation`, which is an `ActivityScenario.recreate()` and the closest
      automated cover for the lifecycle this upgrade touches
- [ ] **A device pass over all four tabs** — could not be completed cleanly; see below. This is why
      the task is `In Review` rather than `Done`.

## Implementation Notes (2026-09-05)

### The upgrade itself is clean

1.5.4 → **1.8.9** (`minCompileSdk=34`, so no compileSdk pressure — unlike OkHttp 5.5.0 in cu-66).
Nothing needed changing: no source edits, no API breaks, unit suite green, and the instrumented
suite green on both emulators.

**Check the resolved version, not the requested one.** A first attempt appeared to succeed while
`app:dependencies` still reported 1.5.4 — the edit had been made against the wrong branch. A green
compile says nothing here, since the app compiles identically against either version.

### The device pass hit an ANR — and it is *not* this upgrade

Tapping the Library tab produced *"Chronicle (debug) isn't responding"*, with the main thread
**Runnable** (not blocked) inside `LayoutInflater` → `AppCompatTextView.<init>` →
`AppCompatDrawableManager.arrayContains`. That is the 196-row library grid inflating, not a
lifecycle deadlock.

**Attribution, tested rather than assumed:** the same sequence on the *previous* build — fragment
1.5.4, everything else identical — reproduces the ANR. So it predates this task.

Two things stop it being a clean finding, and both are recorded rather than papered over:

- Earlier in the same session the Library tab rendered fine several times on this build.
- The tablet was at **58.5 °C** after hours of builds, emulators and playback, and logcat shows
  `Skipped 182 frames`. A thermally throttled device is not a fair baseline.

So the honest statement is: **an ANR exists on this hardware under load, on both versions, and the
fragment upgrade neither causes nor fixes it.** Filed as [[cu-164]] for investigation on a cool
device.

### What the owner should look at

Whether to accept the upgrade on the strength of the instrumented pass, or re-run the four-tab
device check on a rested tablet first. The automated evidence is good — six instrumented tests
across the minSdk floor and a current API — but the task's own third criterion asked for something
no test here can see, and it did not complete.

## Related

- [[cu-69]] — pinned it at 1.5.4 and filed this
- [[cu-52]] — put a lifecycle-scoped collector in every fragment
