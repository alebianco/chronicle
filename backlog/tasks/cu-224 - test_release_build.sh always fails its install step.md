---
id: cu-224
title: "test_release_build.sh always fails its install step"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - tooling
  - bug
milestone: m-3
dependencies: []
priority: low
---

## Description

**`./test_release_build.sh` exits 1 on any machine with a device attached, and always has.** Its
step 3 runs `adb install` against the APK it just built — which is `app-release-unsigned.apk`:

```
adb: failed to install app/build/outputs/apk/release/app-release-unsigned.apk:
  Failure [INSTALL_PARSE_FAILED_NO_CERTIFICATES: ... Attempt to get length of null array]
❌ Installation FAILED
```

An unsigned APK cannot be installed. Android requires a signature, and there is no signing config to
add — signing is on the never-touch list, so this is not a matter of wiring one up here.

**It hides because of the guard above it.** The script exits 0 when no device is connected, so on CI
and on any machine without a tablet plugged in it passes. It only fails for the one person most
likely to be running it during device work.

Confirmed pre-existing rather than caused by the Room bump that surfaced it: stashing the change and
re-running produced the identical failure on Room 2.8.1.

## Why it is only `low`

**The valuable half already works.** Step 2b is the reason this script exists — it asserts that
reflection-dependent classes survive R8, which has broken before — and that runs before the install
and passes (9,145 classes in dex). The failing step is a convenience wrapper around manual testing,
and it prints a manual checklist immediately after.

But a script that always exits 1 is a script people stop believing, and its exit code cannot be used
by anything else while this is true.

## Acceptance Criteria

- [ ] `./test_release_build.sh` exits 0 on a machine with a device attached, when the release build
      and the R8 assertions pass
- [ ] It does **not** acquire a signing config, a keystore, or anything else on the never-touch list
- [ ] Whatever it does instead is stated in the script: skip the install with a clear message, build
      a debug-signed release variant for installability, or drop step 3 and keep the checklist
- [ ] The R8 assertion in step 2b still runs and still fails the script when a class is missing —
      sabotage-verify by removing one from the list it checks
- [ ] `./verify.sh` green

## Notes

Closing status **Done** if the fix is mechanical; **In Review** if it changes what the script builds,
since that is a judgement about what "test the release build" should mean.

Worth deciding whether step 3 belongs at all. The checklist it prints is manual work a human does
anyway, and `capture-screens.sh` plus the instrumented suite now cover more than they did when this
script was written.
