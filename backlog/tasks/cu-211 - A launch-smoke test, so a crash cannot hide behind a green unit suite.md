---
id: cu-211
title: "A launch-smoke test, so a crash cannot hide behind a green unit suite"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - testing
  - trust
milestone: m-3
dependencies: 
  - cu-210
priority: high
---

## Description

**This exists because of a specific failure, and the number is the argument.** The Ktor migration
shipped a 100% launch crash — `PLACEHOLDER_URL` lacked a trailing slash, Ktorfit validates that, and
the clients are `@Singleton` so Hilt built them inside `Application.onCreate`. No window was ever
created; the user bounced to the launcher with no crash dialog.

**1,678 unit tests were green.** Every one of them. The defect was in DI wiring that only runs on a
real Android runtime, and nothing in the suite constructs the Hilt graph end to end.

`./verify.sh` cannot catch this class of defect and should not be expected to. What is missing is one
cheap instrumented test that does what a human does: install, launch, look.

## What to build

An `androidTest` that:

1. Launches `MainActivity` and asserts the process survives — this alone would have caught the
   blocker, because the crash happened before any UI existed.
2. Asserts Home renders its first shelf, so a graph that builds but produces an unusable screen also
   fails.
3. Runs in **mock Plex mode**, so it needs no real server and no credentials.

`ChronicleTestRunner` already exists as the instrumentation runner, so the harness is in place.

## The thing to get right

**It must be cheap enough to always run, and honest about where.** An instrumented test needs a
device or emulator, so it cannot join `./verify.sh`'s eight stages without making the inner loop
require hardware. Two options, and the task should pick deliberately:

- a `verify.sh --device` stage, run before closing any task that touched DI or startup; or
- a CI job on an emulator, which costs minutes per push but never gets skipped.

Prefer whichever the owner will actually run. A gate that is too slow gets disabled, which is worse
than not having it — the same reasoning that keeps Pitest out of the inner loop.

## Acceptance Criteria

- [ ] An instrumented test launches the app and fails if the process dies during startup
- [ ] It asserts a rendered Home shelf, not merely that no exception was thrown
- [ ] **Sabotage-verified against the real defect**: reverting `PLACEHOLDER_URL` to its slash-less
      form makes this test fail. If it does not, it does not do its job
- [ ] It runs in mock mode with no real Plex credentials
- [ ] Where it runs is decided and written down — a stage, a CI job, or both
- [ ] `./verify.sh` green

## Notes

Closing status is **In Review**: it is a test, but the judgement about *where* it runs is a workflow
choice the owner should see.

Related: cu-212 covers the other half of why the crash escaped — the test that disabled the
production check.
