---
id: cu-216
title: "Room 2.8.1 to 2.8.3, which is where KMP support landed"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - debt
  - architecture
milestone: m-3
dependencies: 
  - cu-210
priority: medium
---

## Description

This project is on Room **2.8.1**. **Room's Kotlin Multiplatform support landed in 2.8.3.** That is
the fact cu-194 needed and did not have — its §5 says *"Room is believed KMP-capable at our version
(2.8.1; support landed in the 2.7 line). **Confirm against the Room release notes before relying on
it** — it is the kind of version-dependent claim this repo has been burned by."*

Confirmed, and the belief was wrong about the version: it is 2.8.3, not the 2.7 line, and this
project is two patches short of it.

The bump matters for two reasons beyond being current:

- It **settles SQLDelight**. cu-194 raises SQLDelight only in case Room could not go multiplatform.
  At 2.8.3 it can, so SQLDelight is declined on the merits rather than deferred.
- It is a **patch bump on the most safety-critical storage in the app** — cheap, and much cheaper
  than discovering later that a KMP claim rested on a version nobody checked.

## Not Room 3.0

`androidx.room3` exists and is a deliberate breaking major focused on KMP, adding JS and WASM. It is
**declined for now**:

- currently alpha, against **five databases, nineteen exported schemas and seven migration tests**;
- its headline feature has no consumer — cu-182 has not named a second target, and JS/WASM is not one
  this app would want;
- Room 2.8.3 already provides the Android/iOS/JVM support that a Wear or desktop target would need.

Revisit when cu-182 names a target **and** Room 3.0 is stable.

## The thing to get right

**The migration tests are the whole safety net, so they must run for real.** Gradle's up-to-date
checks make a passing migration suite meaningless if nothing recompiled — use `--rerun-tasks`, which
this repo has already been bitten by twice (the coverage-report staleness in cu-204, and sabotage
verification generally).

Room's codegen also moved to Kotlin output in the 2.8 line. Check the generated `_Impl` classes still
match what `test_release_build.sh` asserts survives R8, and that the JaCoCo exclusion patterns still
catch them.

## Acceptance Criteria

- [ ] Room at 2.8.3 across `room-runtime`, `room-ktx` and `room-compiler`
- [ ] All **nineteen** exported schemas unchanged — a schema diff here means the bump altered
      generated SQL, which is a much bigger conversation
- [ ] The seven migration tests pass with `--rerun-tasks`, not from cache
- [ ] `./test_release_build.sh` still finds every reflection-dependent class in the dex
- [ ] SQLDelight recorded as **declined**, citing 2.8.3's KMP support, so cu-194 can close it
- [ ] Room 3.0 recorded as **declined for now**, with the condition that would change it
- [ ] `./verify.sh` green

## Notes

Closing status **Done** if the schemas are untouched and the migration tests pass on a real rerun —
this is a build-level change with no user-visible surface. If any schema moves, stop and escalate:
that is not a patch bump.
