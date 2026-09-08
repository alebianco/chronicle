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

## api27 is also *flaky* once it does run — measured 2026-09-08

Separate from the setup failure, and found while device-verifying cu-223. With the emulator image
already installed locally, `:app:api27DebugAndroidTest` was run six times against code that differed
only in a test matcher:

| run | result |
|---|---|
| warm | 10/10 pass |
| warm | 10/10 pass |
| warm | 10/10 pass |
| `--rerun-tasks` | **4 failures** |
| `--rerun-tasks` | 10/10 pass |
| `--rerun-tasks` | 10/10 pass |

The failing set was not stable between failures — one run failed `AutoBrowseTreeTest` plus
`LoggedInLaunchTest`, another a different pair — and it included Auto browse-tree assertions that
the change under test could not reach. The two `LoggedInLaunchTest` failures were both
`ComposeTimeoutException` after 30 s waiting for the login state to settle, which is the race
`awaitHomeShelf` already documents at 2.4 s on a real device; api35 passed 10/10 every time.

So the failures correlate with a **cold emulator start** rather than with any code. That matters for
this task twice over: it is a second reason api27 cannot simply be switched back on in CI, where
every run is cold by definition, and it is a caution against reading a single red api27 run as a
real regression — that mistake was made once during this measurement before the six runs settled it.

Worth checking whether the login-settle timeout is simply too short for a cold API 27 image before
concluding anything about AGP.

## Why it matters

The minSdk floor is not decoration. `api27` was chosen because *"a new API called without a version
guard is a live risk at minSdk 27 with Media3 — the kind of break that only shows on an old
device"*. API 35 cannot catch that class of defect.

The launch-crash protection cu-211 exists for is API-independent, so the gate is still worth having
at API 35 alone. This is a real gap, deliberately taken to get the gate running, and recorded rather
than quietly accepted.

## Acceptance Criteria

- [ ] The cause is established — it affects **both** api27 (fresh install) and api35 (restored from
      cache), so it is about how AGP resolves a device ABI at setup time rather than anything
      specific to the API 27 image
- [ ] `api27` runs in CI again, **or** an alternative gives minSdk coverage (a different image
      source such as `aosp-atd`, a different API level near the floor, or a lint/API-desugaring
      check that catches the same defect class)
- [ ] If no fix is found, the decision to run CI at API 35 only is recorded with its reasoning, and
      `ciCheckGroup` keeps its comment explaining the gap
- [ ] `instrumentedCheckGroup` still runs both levels locally
- [ ] Verified by a real CI run, not by local success — local is where this already passes

## Notes

Closing status **In Review**: dropping a test target is a judgement about acceptable risk.

Check whether AGP 9 fixes it — its `testedAbi` is the property the warning points at, and cu-214
stage 3 lands that version. This may resolve itself there, which would make waiting cheaper than
debugging. If so, close this by citing cu-214 rather than duplicating the work.
