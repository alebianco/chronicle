---
id: cu-162
title: Upgrade androidx.fragment past 1.5.4
status: To Do
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

- [ ] `androidx.fragment` moved to a current version, with the resolved version recorded
- [ ] `./verify.sh` green **and** `./verify.sh --instrumented` green
- [ ] A device pass over all four tabs and a background/restore cycle, since the unit suite cannot
      see fragment lifecycle at all

## Related

- [[cu-69]] — pinned it at 1.5.4 and filed this
- [[cu-52]] — put a lifecycle-scoped collector in every fragment
