---
id: cu-222
title: "CI lost the minSdk floor when api27 would not start"
status: In Review
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - tooling
  - testing
milestone: m-3
dependencies:
  - cu-211
priority: medium
---

## Description

**CI runs the instrumented suite on API 35 only.** The API 27 managed device — the minSdk floor —
was dropped from the CI group to get the gate running at all.

On a GitHub runner, `api27Setup` installs the image successfully and then fails:

```
Checking the license for package Intel x86 Atom System Image ... accepted.
"Install Intel x86 Atom System Image API 27 (revision 1)" finished.
> Task :app:api27Setup FAILED
   > Cannot query the value of this property because it has no value available.
```

immediately after AGP warns:

```
api27 has an unspecified testedAbi. This presently defaults to "x86".
However, in 9.0 this will change to "arm64-v8a"
```

**What was tried, so it is not retried blindly:**

- `testedAbi = "x86"`, which the warning itself names, **does not compile**. The property exists on
  `com.android.build.gradle.internal.dsl.ManagedVirtualDevice` and on the `gradle-api` DSL
  interface, but not on what the build script actually binds to in AGP 8.13.2 — "Unresolved
  reference", surviving a daemon stop, a `.gradle` wipe and a kotlin-dsl accessor-cache clear.
- `require64Bit = false` compiles and changes nothing; the same warning and the same failure.
- **It does not reproduce locally.** The image is already installed on the owner's machine, so the
  failing path never executes. That makes this a CI-only debug loop of ~2 minutes per attempt.

## It is not only api27 — a cache made api35 fail the same way

**Update, 2026-09-07.** After the emulator system images were cached to avoid refetching ~1.7 GB per
run, **`api35Setup` began failing with the identical error**, with the identical ABI warning above
it. The run that first restored the cache is the run that broke, and removing the cache restored the
gate.

So the failure is not specific to API 27 or to a 32-bit image. Whatever AGP resolves when it
*installs* a system image is not fully reconstructed by unpacking a cached copy of the directory,
and the symptom is this same unhelpful "no value available".

That reframes the fix: it is about how AGP resolves a device's ABI at setup time, not about API 27's
image being unusual. It also means **the cache stays off** until this is understood — a 40 s download
against a gate that does not run is not a saving.

## The suite fails on a **freshly created AVD**, at *both* API levels — measured 2026-09-08

Found while device-verifying cu-223, and it changes the shape of this task. First read as flakiness;
six more runs showed it is not random at all but reproducible on a clear condition.

| command | api27 | api35 |
|---|---|---|
| `:app:apiNNDebugAndroidTest` (reuses the AVD) | **10/10 pass**, repeatedly | **10/10 pass** |
| `:app:apiNNDebugAndroidTest --rerun-tasks` (recreates it) | **4 failures**, repeatedly | **4 failures** |

A passing run and a failing one were taken back to back on identical code, in both directions, so
this is not timing noise. `--rerun-tasks` reruns `apiNNSetup`, which recreates the emulator; the
tests fail on a **fresh** AVD and pass on a reused one.

**It is not about API 27.** api35 fails the same four tests with the same signatures, which is the
single most useful fact here: the minSdk floor is not what is broken, so switching image source or
API level cannot fix it. `systemImageSource = "default"` was tried on api27 and made no difference —
setup succeeded and the same four tests failed.

The failures are one root cause with two faces:

```
AutoBrowseTreeTest.theBrowseRootIsNotTheEmptyRoot   0.1s   "a seeded session must yield a real
                                                            browse root, got 'empty root'"
LoggedInLaunchTest.launchesIntoTheAppWhenAlreadySignedIn  30.5s  ComposeTimeoutException
```

The browse-tree assertions fail *instantly* — nothing to wait for, the session was never seeded —
and the launch assertions then spend the full 30 s `LOGIN_SETTLE_TIMEOUT_MS` waiting for a login
state that never arrives. So the thing to investigate is **`MockPlexMode.enable` on a first-boot
emulator**, not AGP's ABI resolution.

Raising the timeout would not help the 0.01 s failures, so that is ruled out before being tried.

### What this means for the ATD option

**There is no ATD image below API 30.** `sdkmanager --list` offers `aosp_atd`/`google_atd` from
android-30 upward only, so an ATD substitute at the minSdk floor is impossible — that option is
closed by fact rather than by budget, and needed no CI pushes to establish.

One more correction to the record above: this machine's installed API 27 image is
`default/arm64-v8a`, not the `aosp` x86 image the note describes, and API 27 *does* publish an
arm64-v8a image (`system-images/android-27/default/arm64-v8a`). The original "no arm64 variant"
claim was specific to `aosp`.

## Measured again, 2026-09-09 — the second fault is a **race**, not a deterministic failure

The table above records the suite failing on a fresh AVD and passing on a reused one. Re-measured on
AGP 9.4.0 with the api35 AVD **deleted from disk** between runs, which is stricter than
`--rerun-tasks`:

| run | fresh AVD | failures |
|---|---|---|
| 1 | `--rerun-tasks` only, AVD kept | **0** — 10/10 pass |
| 2 | AVD deleted | **4** |
| 3 | AVD deleted | 0 |
| 4 | AVD deleted | 0 |
| 5 | AVD deleted | **4** |
| 6 | AVD deleted | 0 |

**Two corrections to the record above:**

- **`--rerun-tasks` alone does not reproduce it.** Run 1 passed 10/10 with the flag the table names
  as the trigger. The AVD has to be *gone*, not merely re-set-up.
- **It is not reproducible on a fresh AVD either — it is ~2 in 5.** The earlier reading of
  "reproducible" came from a smaller sample. That matters for how it is debugged: a fix cannot be
  confirmed by one green run.

Always the same four tests, and the root cause is one fact: **`MockPlexMode.isRunning` is false**, so
`mockPlexModeIsActive` fails outright and the other three fail downstream of a logged-out app.

### What the logcat says

The failing run's media session carries:

```
error=No user chosen. Please return to Chronicle and finish logging in
```

which is `LOGGED_IN_NO_USER_CHOSEN`. `MockPlexMode.enable` seeds `accountAuthToken`, `server` and
`library` but **not** `user` — and that is fine, because `determineLoginState`'s
`server != null && library != null -> LOGGED_IN_FULLY` branch is evaluated **before** the
`user == null` one. So reaching "no user chosen" means `server` or `library` read back **null** at
that moment, even though `SharedPreferencesPlexPrefsRepo` writes both with `commit()`.

### FIXED, 2026-09-09 — a lost write in `SettingsDataStore`, not an ABI or a network question

The suspect below was **wrong**, and saying so matters because it was plausible: nothing in
`setupNetwork` or `connectToServer` touches the prefs, and only Settings and the login flows call
`clear()`. A full device logcat settled it in one read:

```
I/DebugHooks : Mock Plex mode is enabled; seeding a fixture-backed session
I/MockPlexServer : MockPlexServer listening on http://127.0.0.1:57437
I/PlexLoginRepo : hasAccountToken = true, hasServerToken = true,  library = null
I/PlexLoginRepo : hasAccountToken = true, hasServerToken = false, library = null
```

Mock mode started **successfully**. The two `determineLoginState` evaluations are **1 ms apart**,
before the network callback fires, and `hasServerToken` flips `true → false` between them. The seed
was not being cleared by anything — the write was being **rolled back**.

**The cause is `SettingsDataStore`.** `set()` moves the in-memory `_snapshot` immediately and
persists on a coroutine, while the collector in `init` replaces the *whole* snapshot with every
`dataStore.data` emission. When an emission predates a pending write — ordinary on a cold device,
where the first disk write is slow — it restores the old value over the new one. `MockPlexMode`
writes `server` then `library`; both were clobbered mid-seed, `determineLoginState` read
`library = null`, and Android Auto served an empty browse root.

Fixed by tracking keys with writes in flight and re-applying them on top of each disk emission.
`remove()` is tracked the same way, since a removal can be rolled back identically.

**Two traps found while fixing it**, both worth recording:

- The declarations must sit **above** `init`. The collector reads them on its first emission, and a
  property declared below throws `NullPointerException: Cannot enter synchronized block` on a
  coroutine thread — which surfaced as eight unrelated `SettingsBackupRepoTest` cases failing
  *before they started*.
- The existing `FakeDataStore` cannot reproduce this: it applies writes before `updateData` returns.
  A fake has to genuinely **suspend**, or the write coroutine completes and models nothing.

### Measured after the fix

| | before | after |
|---|---|---|
| api35, fresh AVD | 3/3 **failed** | **5/5 pass** |
| api27, fresh AVD | (not run) | **3/3 pass** |

Unit-level: `a value written while the disk is lagging survives a stale re-emission` in
`SettingsDataStoreTest`, sabotage-verified — reverting the one-line collector change fails it.

### The original suspect, kept for the record

`ChronicleApplication.setupNetwork` registers a `registerDefaultNetworkCallback` whose `onAvailable`
calls `connectToServer()` — **asynchronously, on a system thread**. On a first-boot emulator network
availability arrives late and at an unpredictable moment, which fits all three observations: it never
happens on a warm AVD, it happens on roughly two fresh boots in five, and it happens identically at
both API levels.

`DebugHooks.onApplicationCreate` is ordered before `setupNetwork` deliberately, and that ordering is
correct — but it only guarantees the *seed* happens first, not that a later async callback cannot
act on a half-initialised config.

**This is where the next session should start**, and it is a narrower question than the ticket
began with: what `connectToServer()` does to a seeded mock session when it fires before the mock
server's `/identity` is reachable. Confirming it needs instrumentation plus several emulator cycles
at ~2 minutes each.

## The `testedAbi` escape hatch is closed on AGP 9 too — checked 2026-09-09

The note at the bottom of this file says to check whether AGP 9 fixes the **setup** half, since
`testedAbi` is the property its warning names. It does not, and that is now measured rather than
assumed: on **AGP 9.4.0**, `testedAbi = "x86"` is still `Unresolved reference`, and
`javap` on `gradle-api-9.4.0.jar` confirms `com.android.build.api.dsl.ManagedVirtualDevice`
exposes `device`, `apiLevel`, `sdkVersion`, `systemImageSource`, `require64Bit` and `pageAlignment`
— **no `testedAbi` at all**.

So the property exists only on AGP's internal implementation class, at 8.13.2 and at 9.4.0 alike.
The build script cannot set it, and no version bump available to this project changes that.

That note also says cu-214 "measured AGP 9 and **skipped** it". That is now stale: cu-214 landed
**AGP 9.4.0**.

## Why it matters

The minSdk floor is not decoration. `api27` was chosen because *"a new API called without a version
guard is a live risk at minSdk 27 with Media3 — the kind of break that only shows on an old
device"*. API 35 cannot catch that class of defect.

The launch-crash protection cu-211 exists for is API-independent, so the gate is still worth having
at API 35 alone. This is a real gap, deliberately taken to get the gate running, and recorded rather
than quietly accepted.

## Acceptance Criteria

- [x] **Both faults re-measured on AGP 9.4.0**, and the record corrected: fault 2 is a **~2-in-5
      race on a first boot**, not a deterministic fresh-AVD failure, and `--rerun-tasks` alone does
      not trigger it. Root cause narrowed to `MockPlexMode.isRunning == false` with the app in
      `LOGGED_IN_NO_USER_CHOSEN`; the suspect is `setupNetwork`'s async
      `registerDefaultNetworkCallback` → `connectToServer()`
- [x] **Fault 1's named escape hatch is closed for good**: `testedAbi` is absent from the
      `ManagedVirtualDevice` DSL interface on **AGP 9.4.0** as well as 8.13.2, verified with
      `javap`. No available AGP version lets the build script set it
- [~] **Fault 2 narrowed, not closed.** A real lost write in `SettingsDataStore` was found and
      fixed (45cc6db5), and it moved the rate a long way: api35 went 3/3 failing to 5/5 passing on
      fresh AVDs, api27 3/3 passing, guarded by a sabotage-verified unit test. **But it recurred on
      2026-09-09**, run 34365259771 — four api27 failures with the original signature, on a branch
      that provably contains the fix. See the section below. Tracked in cu-238
- [x] **Two separate faults, and they must not be conflated.** The `api27Setup` failure ("no value
      available", after the unspecified-ABI warning) is one. The *suite* failing on a freshly
      created AVD at **both** API levels is the other, measured 2026-09-08 and reproducible — it is
      the mock session not seeding on a first-boot emulator, not an ABI question at all. Fixing the
      setup task alone would leave CI red
- [x] `MockPlexMode.enable` investigated on a first-boot emulator, and **cleared**: the logcat shows
      it seeding correctly every time. The fault was one layer down, in the settings store the seed
      writes through
- [x] `api27` runs in CI again, **or** an alternative gives minSdk coverage. **Resolved to YES —
      api27 runs, 10/10 green on run 34350392813.** Superseded the earlier "no" below, which was
      measured honestly but against the wrong cause. Original note: **resolved to "no",
      measured**: re-added to `ciCheckGroup` and run on AGP 9.4.0 without the image cache (run
      34341471957, 2026-09-09), and `api27Setup` failed identically to the 8.13.2 evidence. Worse,
      it produced *no* androidTest results, so api35 lost coverage too — hence the revert. No
      alternative is available either: `aosp-atd` has no image below API 30 and `testedAbi` is
      unsettable at every usable AGP version. A different near-floor API level, or a lint /
      API-desugaring check catching the same defect class, is future work — not this task.
      Original note: **was the only thing between CI and the minSdk floor** — fault 2 was the reason the suite was
      unreliable even locally, and that is gone. Whether fault 1 still blocks a GitHub runner can
      only be answered by a CI run; it does not reproduce on this machine — noting that
      **`aosp-atd` is impossible here**: no ATD image is published below API 30, checked against
      `sdkmanager --list`. That leaves a different API level near the floor, or a
      lint/API-desugaring check that catches the same defect class
- [x] ~~If no fix is found, the decision to run CI at API 35 only is recorded with its reasoning~~
      — **retired: a fix was found.** `ciCheckGroup` now runs both levels, and the comment records
      the AGP bug, the run id and why the ABI and cache theories were wrong — done: the comment now records the
      re-measurement, the run id, the reason re-adding api27 is worse than the gap it closes, and an
      explicit "do not re-add expecting a different result"
- [x] `instrumentedCheckGroup` still runs both levels locally — unchanged, `app/build.gradle.kts`
      still adds both `api27` and `api35` to that group
- [x] Verified by a real CI run, not by local success — local is where this already passes. Run
      34341471957 is that run, and it is what converted fault 1 from "might be fixed by AGP 9 or the
      cache removal" into a measured no

## api27 is back in `ciCheckGroup` as a probe — pushed 2026-09-09

Fault 2 being fixed does **not** imply fault 1 is fixed, and the two must not be conflated (see the
AC above): `api27Setup` fails while *creating the emulator*, before the APK is installed and before
any test runs, so a lost-write fix in app code can neither address nor mask it. The proof is that
fault 2 failed identically on api35, where `api27Setup` never executes.

What *is* genuinely unknown is whether fault 1 still exists. Its evidence dates from AGP 8.13.2 and
from before the emulator image cache was removed — the two changes most likely to perturb image
installation — and api27 has not run on a GitHub runner since either landed. It does not reproduce
locally at all, because the image is already installed here, so the failing path never executes.

So api27 was added back to `ciCheckGroup` to measure it, which is the only instrument that can.

### Answer: fault 1 survives, byte-for-byte — run 34341471957, 2026-09-09

```
> Task :app:api27Setup
The device "api27" does not specify a "testedAbi".
explicitly set the ABI: testedAbi = "x86"
> Task :app:api27Setup FAILED
   > Cannot query the value of this property because it has no value available.
```

`api27Setup` failed in 1m 15s and **no androidTest results were produced at all** — the emulators
never ran, so api35 lost its coverage too. That is the concrete cost of leaving api27 in the group:
it does not merely fail its own device, it takes the whole gate down with it.

Neither of the two changes that raised the hope moved it: this is AGP **9.4.0**, with no image
cache, and the failure is identical to the 8.13.2 evidence — same ABI warning, same
"no value available". Fault 1 is not a cache artefact and not an AGP-version artefact.

**So api27 is reverted out of `ciCheckGroup`**, and the remaining criteria resolve to the fallback:
record the API-35-only decision with its reasoning. `aosp-atd` is already ruled out (no ATD image
below API 30), and `testedAbi` is ruled out at every usable AGP version. What is left, for a future
task rather than this one, is a different API level near the floor, or a lint/API-desugaring check
catching the same defect class without an emulator.

Note the build-script half was verified locally as far as local can go — `./gradlew tasks`
configures and `ciCheckGroupGroupDebugAndroidTest` resolves — which is precisely why it took a CI
run to learn anything.

### Unrelated, found in the same run

`RawDurationFormatTest > no player source contains a literal raw duration pair` failed with
`java.lang.StackOverflowError at Pattern.java:4847`. It passes locally on `--rerun-tasks` and
predates this probe (the test last changed in 50095985); a runner's smaller default thread stack
exposes catastrophic backtracking in the guard's regex. **Not part of cu-222** — filed separately,
since it fails `verify.sh` on CI independently of anything here.

## SOLVED — an unguarded provider in AGP, not the ABI and not the runner. Run 34350392813, 2026-09-09

**api27 runs on CI again: `TEST-api27.xml`, tests=10 failures=0 errors=0**, alongside api35 at
10/10. That file had never existed in this project's CI history.

Everything this ticket previously recorded as the cause was wrong. Found by reading AGP 9.4.0's
bytecode, after the ABI hypothesis was eliminated by measurement.

### The mechanism

`ManagedDeviceInstrumentationTestSetupTask$ManagedDeviceSetupRunnable` guards the system image it
resolves, then does **not** guard the emulator directory:

```
offset  60: sdkImageDirectoryProvider(...).isPresent   <- checked, and AGP has a written-out
offset  65: ifne 261                                      error message for the missing case
            ...else generateSystemImageErrorMessage()
offset 262: getEmulatorDirectoryProvider()
offset 265: Provider.get()                             <- bare .get(), no isPresent check
```

If the `emulator` SDK package is not installed yet, that provider holds no value and **Gradle**
throws "Cannot query the value of this property because it has no value available" — naming no
property, which is exactly why two months of notes never suspected the emulator.

The runner's own timestamps prove the race:

```
11:57:17.025  api27Setup: Preparing "Install Android Emulator v.37.1.11"
11:57:17.531  api27Setup: x86_64 system image finished
11:57:18.527  api27Setup FAILED
11:57:23.732  "Install Android Emulator v.37.1.11" ready     <- 5 s after the task died
```

### Every earlier observation follows from this

| Observation | Explanation |
|---|---|
| Never reproduced locally | A dev machine already has `emulator` installed, so the provider always has a value. **Not** "the image is pre-installed so the failing path is skipped" — that was this ticket's wrong reading. |
| api35 succeeded on the same runner | It ran **second**, after api27's own attempt had installed the emulator. |
| The image cache broke api35 | Caching made api35 the **first** device to reach the unguarded call. The cache changed *ordering*; it corrupted nothing. |
| `require64Bit`/ABI changed nothing | Correct — the ABI was never involved. x86_64 installed cleanly and setup still failed. |
| `testedAbi` looked like the fix | A red herring throughout. AGP's warning points at an unrelated property. |

### The fix

One CI step, before Gradle runs: `sdkmanager --install emulator`, asserting the binary exists rather
than trusting the exit code — the failure mode is precisely that a missing directory resurfaces
later as an unrelated-looking error inside a setup task.

### Corrections to this ticket's own record

Three claims on file here were wrong and are superseded:

1. **"It does not reproduce locally because the image is already installed, so the failing path
   never executes."** Wrong reason. It is the *emulator package*, not the system image.
2. **"At API 27 the AOSP image is 32-bit x86 with no arm64 variant."** False.
   `system-images;android-27;default;x86_64` and `;arm64-v8a` are both published.
3. **"Not fixable from the build script / accept the API-35-only gap."** False. It was fixable — in
   CI configuration rather than the build script, which is why searching the DSL never found it.

`require64Bit = true` is kept: forcing a 64-bit image on an x86_64 runner is correct regardless, and
it is a no-op on arm64 where the image is already 64-bit.

**Follow-up worth measuring, not assumed:** the emulator image cache was removed for a reason now
known to be wrong. Caching ~1.7 GB may be safe now that the emulator is installed up front. Left off
deliberately, because that is unmeasured and the gate is not worth risking on a guess.

## Fault 2 recurred — run 34365259771, 2026-09-09. This task was closed too confidently.

Found incidentally while benchmarking two CI cache configurations, on
`experiment/setup-gradle-basic`. Four api27 failures, api35 clean 10/10:

```
AutoBrowseTreeTest.theBrowseRootIsNotTheEmptyRoot
  java.lang.AssertionError: a seeded session must yield a real browse root, got 'empty root'
AutoBrowseTreeTest.theRootOffersEveryCategoryByItsStableId
LoggedInLaunchTest.launchesIntoTheAppWhenAlreadySignedIn
LoggedInLaunchTest.survivesRecreation
```

That is fault 2's original signature exactly, and **not** the AGP setup bug: `api27Setup`
*succeeded* on this run. `git merge-base --is-ancestor` confirms 45cc6db5 — the `SettingsDataStore`
fix — is present on the branch that failed, so this is a genuine recurrence rather than a stale
checkout.

Rate across today's runs: **6 passes, 1 failure (~14%)**, down from the ~40% measured on
2026-09-09 before the fix. So the fix helped materially and the remaining fault is rarer, but real.

### What went wrong with the record, not just the code

The closing criterion cited "3/3 failing to 5/5 passing" as proof. Five consecutive passes cannot
establish a fix for a fault this ticket had **already measured at ~2 in 5** — the probability of
five clean runs on an unfixed ~40% fault is about 8%, which is unlikely but nowhere near excluded.
This ticket's own earlier correction says it outright: *"A fix therefore cannot be confirmed by one
green run."* That warning was written here and then not applied to the fix that followed it.

The lesson is about the closing standard, not the diagnosis: a fix for a probabilistic fault needs a
run count derived from the measured rate, and `Done` should not have been claimed from five.

**What remains true and is not reopened:** fault 1 (the AGP unguarded-provider bug) is genuinely
fixed and api27 runs on CI — that half is unaffected by this recurrence.

## Notes

Closing status **In Review**, revised 2026-09-09: this was briefly `Done`, which was wrong. Fault 1
is machine-proved (api27 runs on CI, 10/10), but fault 2 recurred after being called fixed — see the
recurrence section. The remaining work is split out to **cu-238** so this task's fault-1 result is
not held hostage to it; the owner decides whether to close this on fault 1 alone.

Original note: dropping a test target is a judgement about acceptable risk.

~~Check whether AGP 9 fixes the setup half — its `testedAbi` is the property the warning points
at.~~ **Answered 2026-09-09: it does not.** `testedAbi` is absent from the `ManagedVirtualDevice`
DSL interface at AGP 9.4.0 as well as at 8.13.2 — verified with `javap`, not inferred. This note
also said cu-214 "skipped" AGP 9; that is stale, cu-214 landed **AGP 9.4.0**.

It would not address the second fault regardless: a fresh AVD fails the suite on api35 too, where
setup succeeds and no ABI warning appears. That half is now known to be a **race** — see the
2026-09-09 section above — so a fix for it must be confirmed over repeated runs, never one green
one.
