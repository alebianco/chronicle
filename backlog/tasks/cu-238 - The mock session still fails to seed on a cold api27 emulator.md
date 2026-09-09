---
id: cu-238
title: "The mock session still fails to seed on a cold api27 emulator"
status: To Do
assignee: []
labels:
  - testing
  - flaky
  - plex
milestone: m-3
dependencies: []
priority: high
---

## Description

Four instrumented tests fail on a cold api27 emulator because the seeded mock Plex session reads
back empty:

```
AutoBrowseTreeTest.theBrowseRootIsNotTheEmptyRoot
  java.lang.AssertionError: a seeded session must yield a real browse root, got 'empty root'
AutoBrowseTreeTest.theRootOffersEveryCategoryByItsStableId
LoggedInLaunchTest.launchesIntoTheAppWhenAlreadySignedIn
LoggedInLaunchTest.survivesRecreation
```

This is the remainder of cu-222's "fault 2". That task found and fixed a real cause — a lost write
in `SettingsDataStore` where the `init` collector replaced the whole snapshot on every
`dataStore.data` emission, rolling back a pending write (45cc6db5, sabotage-verified unit test).
The fix is present and helped materially. It did not eliminate the fault.

## Evidence

- **Recurrence:** run 34365259771, 2026-09-09, on `experiment/setup-gradle-basic`. Verified with
  `git merge-base --is-ancestor 45cc6db5` that the branch contains the fix.
- **Not the AGP setup bug:** `api27Setup` *succeeded* on that run. cu-222's fault 1 is separately
  fixed and is not in question here.
- **api35 was clean** (10/10) on the same run, as it has been throughout.
- **Rate:** 6 passes / 1 failure across 2026-09-09's runs (~14%), down from ~40% before 45cc6db5.

## Why it is worth chasing rather than retrying

A ~14% failure on the gate that guards the minSdk floor will fire roughly once a week on an active
branch, and its symptom — an empty Android Auto browse root — is indistinguishable from a real
product defect. It also erodes the gate's credibility, which is the one thing cu-222 spent two
months restoring.

## Where to start

cu-222's logcat evidence showed `determineLoginState` evaluating twice ~1 ms apart with
`hasServerToken` flipping true→false and `library` null throughout, *before* any network callback.
The `SettingsDataStore` write-in-flight fix addressed one path to that. Candidates for the rest:

- Another read path that bypasses the in-flight tracking (a direct `dataStore.data` collector
  elsewhere, or a second store instance).
- `MockPlexMode.enable` racing the app's own first read, rather than the write being lost — the
  earlier investigation cleared the *seeding*, not the ordering against startup.
- Something specific to API 27's slower cold boot that widens whatever window remains; note it has
  never been observed on api35.

## Acceptance Criteria

- [ ] The failure is **reproduced locally** before any fix — a cold/deleted AVD at api27, and enough
      runs to see it at the measured rate. A single green run proves nothing here
- [ ] The remaining cause is identified by measurement (logcat or instrumentation), not inferred
- [ ] Fixed, with a test that fails when the fix is reverted
- [ ] **Confirmed over a run count derived from the measured rate**, not an arbitrary 3 or 5. At
      ~14%, ten consecutive passes still leave ~22% chance of a fluke — state the arithmetic used
      and pick a number that makes a fluke genuinely unlikely
- [ ] cu-222's fault-2 criterion updated to point here, and closed only when this is

## Notes

**The process lesson from cu-222, which is the reason this is a separate task.** Fault 2 was closed
`Done` on "3/3 failing to 5/5 passing" for a fault the same ticket had already measured at ~2 in 5.
Five passes against an unfixed 40% fault happen about 8% of the time — unlikely, but not excluded.
The ticket even carried the warning *"a fix therefore cannot be confirmed by one green run"* and
then did not apply it.

So: for a probabilistic fault, derive the confirmation count from the rate before claiming a fix.
