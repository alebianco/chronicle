---
id: cu-238
title: The mock session still fails to seed on a cold api27 emulator
status: Done
assignee: []
created_date: ''
updated_date: '2026-09-10 06:58'
labels:
  - testing
  - flaky
  - plex
milestone: m-2
dependencies: []
priority: high
ordinal: 74000
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

## Code reading, 2026-09-09 — a specific remaining race, not yet reproduced

Traced the read path the failing assertion depends on. `MockPlexMode.enable` writes
`plexPrefs.server`, and `SharedPreferencesPlexPrefsRepo.server`'s getter returns **null** unless
*all* of name, token and connections read back. Three of those four values go through
`SettingsDataStore` (`PREFS_SERVER_NAME_KEY`, `..._ID_KEY`, `..._IS_OWNED`,
`PREFS_SERVER_CONNECTIONS_KEY`); the access token goes to a separate `authPrefs` file via
`commit()`. So the DataStore is on the critical path, as cu-222 found.

**The gap the 45cc6db5 fix leaves.** `set()` does, in order:

```kotlin
_snapshot.value = ...updated...              // 1. snapshot moves
synchronized(pendingLock) { pendingWrites += key }   // 2. key marked in flight
writeScope.launch { ... }                    // 3. disk write
```

and the collector does `_snapshot.value = fromDisk.withPendingWrites()`.

Steps 1 and 2 are **not atomic together**. A disk emission that lands between them is re-applied
using a `pendingWrites` set that does not yet contain this key, so `withPendingWrites()` skips it
and the fresh snapshot value is overwritten by the older disk value. The window is a few
instructions, which fits a fault that went from ~40% to ~14% rather than to zero: the fix closed
the *wide* window (writes already in flight) and left a narrow one.

There is a second, similar hazard in `withPendingWrites()` itself: it reads `_snapshot.value` at
line 84 *after* taking the `pendingWrites` snapshot at line 82, so a `set()` completing between
those two reads can be partially observed.

**Why api27 and not api35:** a slower cold boot lengthens the first `dataStore.data` emission's
arrival, making it likelier to interleave with `MockPlexMode`'s four rapid `set()` calls.

### RETRACTED: the "400/400 reproduction" below was an artefact of a broken probe

**Correction, 2026-09-09, later the same day.** The 400/400 figure recorded below is wrong, and the
mechanism it claimed to prove is unproven. Reported here rather than quietly deleted, because the
next reader would otherwise inherit a confident wrong cause — the exact failure this task exists to
correct in cu-222.

The probe's emitter did:

```kotlin
store.state.value = mutablePreferencesOf(other to (n % 2 == 0))
```

which **replaces the whole store state**, discarding any key already persisted. A real
`DataStore` emission reflects the file and retains prior keys, so the probe modelled a store that
destroys its own data. Every "lost write" was the probe deleting the key, not the app losing it.

The control (0/400 with the racing thread disabled) did not catch this, because with no emissions
nothing overwrote the state — the control only proved emissions were involved, which a destructive
emitter guarantees trivially.

**With a faithful emitter** (preserving other keys, toggling one), measured 200 attempts per run:

```
unfixed:  0, 5, 1, 3, 1  per 200   (mean ~2.0, i.e. ~1 %)
"fixed":  1, 8, 6, 2     per 200   (mean ~4.3)
```

So a real race exists at roughly 1 % in this harness, but the candidate fix — marking the key
pending before moving the snapshot, both under one lock, plus reading the pending set and snapshot
together — **does not improve it and may be worse**. The ranges overlap and the fixed mean is
higher. That fix has been reverted, not committed.

**Where this leaves cu-238:** the fault is real and still open. The `set()` ordering hypothesis is
*disproven as the dominant cause*. The next attempt should instrument the actual interleaving —
which thread writes `_snapshot.value` last, and with what — rather than reason from the code shape,
since two readings of that shape have now been wrong.

### Superseded: the original claim, kept for the record — 400/400 with a control

A JVM probe on **real threads** (not `runTest`'s virtual time, which cannot interleave two adjacent
statements): a `DataStore` whose `updateData` sleeps 2 ms, one `set()` of
`PREFS_SERVER_NAME_KEY`, and a second thread pushing 60 unrelated disk emissions across it.

```
with racing emissions:      lost 400 / 400 writes
without (control, RACE=0):  lost   0 / 400 writes
```

The control is what makes this evidence rather than a coincidence: the same probe with the racing
thread disabled loses nothing, so the loss is caused by the interleaving and not by the probe's own
timing. The existing guard test cannot catch this — it emits *after* `set()` returns, which is the
wide window the 45cc6db5 fix already closed.

So the mechanism is confirmed: **a disk emission arriving between the snapshot move and the
pending-mark rolls the write back.** The 400/400 rate is for a deliberately hostile interleaving; on
a device the window is a few instructions wide, which is consistent with ~14% on a slow cold boot.

**Still to do before a fix lands:** the fix itself, and confirmation at a run count derived from the
device-observed rate. The candidate is to mark the key pending *before* moving the snapshot and take
both under `pendingLock`, and to have `withPendingWrites()` read the pending set and the snapshot
under the same lock — the second hazard noted above. Cheap, but it must be sabotage-verified against
this probe promoted to a real test, not committed on the strength of the analysis.

## Instrumented, 2026-09-09 — the mechanism is now measured, and the obvious fix does not close it

Third attempt, done the way the retraction above prescribed: instrument every `_snapshot.value`
assignment with thread + site + value, run until a loss occurs, and **read** the ordering. No
hypothesis from code shape this time.

### What the trace shows

A loss caught at the natural rate (~1 %, 1/200, control 0/600 across three race-off runs):

```
Test worker              |set.marked      |server_name=Mock Plex Server
worker-3 @coroutine#107  |collect.fromDisk|unrelated=false
worker-3 @coroutine#107  |wpw.live        |server_name          <- guard active, re-applies
worker-3 @coroutine#107  |collect.applied |unrelated=false,server_name=Mock Plex Server
worker-2 @coroutine#108  |set.persisted   |...
worker-2 @coroutine#108  |set.unmarked    |...                  <- key leaves pendingWrites
worker-2 @coroutine#107  |collect.fromDisk|unrelated=true       <- STALE, read before the write
worker-2 @coroutine#107  |wpw.live        |                     <- nothing tracked any more
worker-2 @coroutine#107  |collect.applied |unrelated=true       <- server_name gone
```

**The real defect, stated precisely:** pending-tracking is keyed on *write completion*, but
correctness requires it to outlast *every emission that predates the write*. A `dataStore.data`
emission whose file read began before the write landed can be **delivered after** it. By then
`pendingWrites` no longer holds the key, and the stale value is applied unopposed.

This is a wider window than either earlier reading guessed. Both previous hypotheses concerned the
few instructions between the snapshot move and the pending-mark; the measured hole is the entire
interval between the write landing and the last in-flight emission draining — unbounded, and
naturally longer on a slow cold boot, which is the api27-only observation.

### The candidate fix, and why it fails

Tried: hold the *intended value* per key (`Map<Key, Intent>` rather than `Set<Key>`), do the mark
and the snapshot move together under `pendingLock`, read the pending set and the incoming disk
state under the same lock, and — the actual change — **clear an intent only when an emission
carries the intended value**, i.e. when disk demonstrably agrees, rather than when
`dataStore.edit` returns.

It does not work, and the trace says why in one line:

```
collect.fromDisk|unrelated=false,server_name=Mock Plex Server   <- disk agrees, intent settles
collect.fromDisk|unrelated=true                                 <- stale, arrives next, undefended
```

The write's **own** emission satisfies "disk agrees", and it necessarily arrives *before* the stale
emission still queued behind it. So disk agreement is not evidence of ordering either. Measured
with the fix applied, four runs of 200 at the natural rate: `93, 0, 0, 0`. The 93 is not noise
around 1 % — it is the same interleaving, and the spread across identical runs shows this harness's
rate is dominated by host scheduling and is **not a usable measure of the device fault**.

Reverted. Not committed.

### What the next attempt should and should not do

Ruled out by measurement, do not retry:

- The `set()` mark/move ordering (attempt 2, retracted above).
- Settling pending writes on disk agreement (this attempt). Any rule of the form "stop defending
  the key once *some* evidence arrives" fails, because a staler emission can always be behind the
  evidence.

The shape of a fix that could work: **make staleness detectable rather than inferred.** The
collector cannot currently tell an emission that predates a write from one that follows it — it
sees only values. A monotonic write counter stored *in the preferences themselves*, or comparing
against the `Preferences` instance the write returned rather than against values, would give the
collector an ordering to test. Alternatively, stop merging disk into the snapshot at all once the
store has been seeded, and treat `set` as authoritative — the snapshot's purpose is synchronous
reads of values this process wrote, and nothing else writes this file.

Also note the harness limitation above: **this JVM probe cannot confirm a fix.** Its rate swings
0–93 per 200 between identical runs. Confirmation has to come from the api27 instrumented job at a
run count derived from the ~14 % device rate, per the acceptance criteria.

## Fixed, 2026-09-09 — the snapshot is authoritative once seeded

After the third failed patch above, the premise behind every attempt was checked instead of the
patch: **why is disk merged into the snapshot at all?** It is only needed if something other than
this class writes the file. Validated that nothing does:

| Premise | Evidence |
|---|---|
| One `DataStore` over that file in production | one construction site, `SettingsDataStore.create` |
| One `SettingsDataStore` instance | `AppModule.provideSettingsDataStore`, `@Provides @Singleton` |
| One process | no `android:process` in any manifest |
| Restore cannot race a live snapshot | Auto Backup restores pre-process-start; DataStore writes under `files/datastore/`, and the backup rules name only the `sharedpref` and `database` domains |
| The transactional import path stays correct | `SettingsBackupRepo` is the only `edit()` caller, same instance, and `edit()` assigns the snapshot itself |
| Migration does not need the collector | `SharedPreferencesMigration` runs *inside* `dataStore.data.first()`, so the synchronous seed already observes migrated values |
| No test wanted external-change propagation | the only test pushing an external emission asserts the snapshot must **not** change |

So the collector was removed. `dataStore.data` is read exactly once, synchronously, in `init`;
after that only `set`, `remove`, `clear` and `edit` move `_snapshot`. A late stale emission now has
no path to the snapshot at all, rather than being detected and out-raced. Net -64/+30 lines, most
of the additions documentation.

### Measurements

The JVM probe, unchanged from the instrumented run above:

```
                                deterministic late emission   natural rate
before (collector present)              60 / 60               1 / 200
after  (collector removed)               0 / 60               0 / 1000
```

**Sabotage-verified**, because a green probe was twice a broken probe on this task. Restoring the
collector returns the probe to `60/60` and `1/200`, so the harness still detects the fault and the
zeros are real. Note the sabotage restores the collector *without* the pending-write bookkeeping
and still only loses 1/200 naturally — confirming the ~1 % natural rate was dominated by this
mechanism, not by the bookkeeping's ordering hole.

New regression test: `a write survives a stale emission delivered after it reaches disk`. It emits
*after* the write has fully landed, which is the case the pre-existing stale-re-emission test
cannot reach. Both tests fail under sabotage with the expected assertion messages.

Coverage fell 57.87 % -> 57.79 % because well-tested code was deleted; baseline lowered
deliberately via `./coverage-ratchet.sh --update`.

### What is not yet proved

`./verify.sh` passes all 10 stages, but **the device fault itself has not been re-measured**. The
JVM probe cannot stand in for that: its rate swung 0–93 per 200 between identical runs earlier
today. The remaining criteria — reproduction on a cold api27 AVD and confirmation at a count
derived from the ~14 % device rate — still need the instrumented job. This task stays open until
then; a passing unit suite is what cu-222 mistook for a fix.

## The remaining cause: the SAME bug in CredentialStore

Fixing `SettingsDataStore` was necessary and not sufficient. The local reproduction below still
failed at ~8 % afterwards, which is what forced the read path to be traced the rest of the way
rather than the rate to be re-measured.

`SharedPreferencesPlexPrefsRepo.server` returns null unless name, token **and** connections all read
back. Three of those go through `SettingsDataStore` — but the **access token goes to
`CredentialStore`**, a third store in `no_backup/` that had not been examined. It carried the
identical collector, with no in-flight protection at all:

```kotlin
scope.launch { dataStore.data.collect { snapshot.value = it } }   // whole snapshot, every emission
```

`put()` moves the snapshot and then writes; an emission whose file read began before the write can
be delivered after it and drop the value. On a cold start that is precisely the login path: the file
does not exist yet so the first read is slow, while sign-in (and `MockPlexMode`) writes the account
token and then the server token back to back during `Application.onCreate`. A dropped server token
reads as `server == null` — a signed-in app that looks signed out, i.e. the empty browse root.

### Measured

Stale-but-faithful emitter racing one `put`, 400 attempts each:

```
with the collector      16 / 400 lost
without (the fix)        0 / 400 lost
control, race disabled   0 / 400 lost
```

The emitter preserves the keys it read, so a loss is the store dropping the write rather than the
probe deleting it — the property the retracted probe lacked.

### Why it hid through two rounds of fixing

**`CredentialStoreTest` did not exist.** The same mechanism lived in two files and only one was
under test, so two correct-looking fixes to the settings store never touched the token path. The
class now exists with four tests; both race tests fail when the collector is restored
(sabotage-verified), and the two unrelated ones correctly do not.

Audited for a third occurrence: `dataStore.data` is now read exactly once, via `.first()`, in both
stores, and no other class collects a `DataStore` into a snapshot.

Fix: `1f6fa87c`. Same structural shape as the settings store — read once at init, nothing re-applies
it. Writes here were already synchronous, so snapshot and disk cannot diverge, and the file is in
`no_backup/`, excluded from backup and transfer by construction.

## Local reproduction and confirmation on device

Harness: `cold-api27.sh`, deleting the gradle-managed AVD between every run so each is genuinely
cold — `clearPackageData` is not set, so removing the AVD is what makes the *app data* cold too,
which is the condition the fault needs. It asserts on `TEST-api27.xml` contents, not the exit code
(a managed-device task that boots nothing exits 0), and fails a run as `ERROR` if fewer than two
test classes appear.

**That last guard exists because the first version of this harness was wrong.** A comma-separated
`runnerArguments.class` list silently ran only the *first* class, so it sampled `AutoBrowseTreeTest`
alone and never ran `LoggedInLaunchTest` — which holds two of the four failing tests. Five "PASS"
results were collected over half the surface before this was caught, and discarded. A green harness
was the defect twice on this task; the class-count assertion is what stops a third time.

```
pre-fix  (SettingsDataStore fix only)   1 fail / 12 cold runs   (~8 %)
post-fix (both fixes)                   0 fail / 60 cold runs
```

The pre-fix failure was exact — all four tests this task names, with its verbatim assertion
`a seeded session must yield a real browse root, got 'empty root'`. `mockPlexModeIsActive` passed in
both classes on that run, so the fixture server *was* up and seeding *did* run: what failed is the
seeded values surviving to the read. The tests that passed alongside it walk the tree off whatever
root exists, and one of them expects empty — a pattern consistent with a lost seed and not with a
broken fixture.

### The arithmetic, and the full run

An initial 60 clean runs cleared the point estimate (0.54 % fluke) but **not** the 95 % lower bound
(40.7 %), so the criterion was left open rather than rounded to a pass. The derived count was then
run to completion overnight, 2026-09-09/10:

```
                            rate     P(308 clean | unfixed)
pre-fix point estimate      8.3 %    2.3e-12
pre-fix 95 % lower bound    1.5 %    0.99 %      -> 99.0 % confidence
```

**308 clean cold runs, 0 failures, 0 errors**, every one recording `tests=10 classes=2`. 308 is
exactly the count `log(0.01)/log(1-0.015)` requires, so the conservative bar is met at the number
the measurement demanded rather than a number chosen for convenience.

The count is large because the 12-run control cannot distinguish a 1.5 % fault from a 30 % one —
a limit of the control, not of the fix. Green-counting is the weakest evidence available here and
gets weaker the rarer the fault is, so it corroborates rather than carries:

- both faults reproduced deterministically in-process (60/60 and 16/400) and driven to zero
- both sabotage-verified — restoring the collector returns each probe to failing
- controls that *can* fail for the reason under test (faithful emitters, 0/400 and 0/600 with the
  race disabled)
- the lossy code path no longer exists in either store, and no third store has it

Note also that arm64 (local) reproduced a fault only ever seen on x86_64 CI, so the harness is
sampling the real thing rather than a local artefact. Docker was considered for an arch-matched
run and rejected: macOS has no `/dev/kvm` and gives containers no nested KVM, so an x86_64 emulator
there falls back to software CPU emulation — impractically slow, and a *third* timing environment
rather than a closer match to CI's KVM.

## Acceptance Criteria

- [x] The failure is **reproduced locally** before any fix — a cold/deleted AVD at api27, and enough
      runs to see it at the measured rate. A single green run proves nothing here
      — 1 fail / 12 cold runs, all four named tests, verbatim assertion
- [x] The remaining cause is identified by measurement (logcat or instrumentation), not inferred
      — instrumented `_snapshot.value` trace for the first cause, then the read path traced to
      `CredentialStore` and probed (16/400 with the race, 0/400 control)
- [x] Fixed, with a test that fails when the fix is reverted — both stores, both sabotage-verified.
      `CredentialStoreTest` is new; its absence is why this survived two rounds of fixing
- [x] **Confirmed over a run count derived from the measured rate.** **308 clean cold runs**, the
      count derived from the 95 % lower bound of the measured pre-fix rate, all 308 with
      `tests=10 classes=2`. Fluke probability 0.99 % at that bound (99.0 % confidence) and 2.3e-12
      at the point estimate. Arithmetic in the section above
- [x] cu-222's fault-2 criterion updated to point here, and closed only when this is — cu-222's
      criterion carries `[~]` referencing this task, and this task is now confirmed

## Notes

**The process lesson from cu-222, which is the reason this is a separate task.** Fault 2 was closed
`Done` on "3/3 failing to 5/5 passing" for a fault the same ticket had already measured at ~2 in 5.
Five passes against an unfixed 40% fault happen about 8% of the time — unlikely, but not excluded.
The ticket even carried the warning *"a fix therefore cannot be confirmed by one green run"* and
then did not apply it.

So: for a probabilistic fault, derive the confirmation count from the rate before claiming a fix.
