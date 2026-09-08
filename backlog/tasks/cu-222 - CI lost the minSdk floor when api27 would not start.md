---
id: cu-222
title: "CI lost the minSdk floor when api27 would not start"
status: To Do
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

## Why it matters

The minSdk floor is not decoration. `api27` was chosen because *"a new API called without a version
guard is a live risk at minSdk 27 with Media3 — the kind of break that only shows on an old
device"*. API 35 cannot catch that class of defect.

The launch-crash protection cu-211 exists for is API-independent, so the gate is still worth having
at API 35 alone. This is a real gap, deliberately taken to get the gate running, and recorded rather
than quietly accepted.

## Acceptance Criteria

- [ ] **Two separate faults, and they must not be conflated.** The `api27Setup` failure ("no value
      available", after the unspecified-ABI warning) is one. The *suite* failing on a freshly
      created AVD at **both** API levels is the other, measured 2026-09-08 and reproducible — it is
      the mock session not seeding on a first-boot emulator, not an ABI question at all. Fixing the
      setup task alone would leave CI red
- [ ] `MockPlexMode.enable` investigated on a first-boot emulator, since that is what the second
      fault points at. The browse-tree assertions fail in **0.01 s** with "got 'empty root'", so a
      longer `LOGIN_SETTLE_TIMEOUT_MS` is already ruled out as the fix
- [ ] `api27` runs in CI again, **or** an alternative gives minSdk coverage — noting that
      **`aosp-atd` is impossible here**: no ATD image is published below API 30, checked against
      `sdkmanager --list`. That leaves a different API level near the floor, or a
      lint/API-desugaring check that catches the same defect class
- [ ] If no fix is found, the decision to run CI at API 35 only is recorded with its reasoning, and
      `ciCheckGroup` keeps its comment explaining the gap
- [ ] `instrumentedCheckGroup` still runs both levels locally
- [ ] Verified by a real CI run, not by local success — local is where this already passes

## Notes

Closing status **In Review**: dropping a test target is a judgement about acceptable risk.

Check whether AGP 9 fixes the **setup** half — its `testedAbi` is the property the warning points
at. cu-214 measured AGP 9 and **skipped** it (five incompatibilities, three silent), so that escape
hatch is closed for now rather than merely pending.

It would not address the second fault regardless: a fresh AVD fails the suite on api35 too, where
setup succeeds and no ABI warning appears.
