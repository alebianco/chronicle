---
id: cu-224
title: "test_release_build.sh always fails its install step"
status: In Review
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

- [x] `./test_release_build.sh` exits 0 on a machine with a device attached, when the release build
      and the R8 assertions pass — **verified with the tablet attached: exit 0**, 9,382 classes in
      dex, 23 `@Serializable` models checked
- [x] It does **not** acquire a signing config, a keystore, or anything else on the never-touch list
- [x] Whatever it does instead is stated in the script: it **skips the install with a message naming
      the reason** (the APK is unsigned and there is no release signing config), and prints the
      manual checklist either way
- [x] The R8 assertion in step 2b still runs and still fails the script when a class is missing —
      **sabotage-verified**: adding one absent class made it exit **1** with two `❌` lines, and the
      sabotage was reverted in a separate step
- [x] `./verify.sh` green

## What it does now, and why not the alternatives

Step 3 skips, and says why. The two other options in the criterion were considered and rejected:

- **Building a debug-signed release variant** so the install works would install a *different
  artifact* from the one under test — a weaker check that looks stronger.
- **Dropping step 3 entirely** loses the message. A reader who expects an install wants to know why
  there is not one, and "the release APK is unsigned" is that answer.

The valuable half was always step 2b, and it is untouched: it runs before this point and is what
gives the script its exit code.

## Notes

Closed **In Review**: the fix is mechanical in code but it is a judgement about what "test the
release build" should mean — the script no longer attempts an install at all, which is a scope
decision rather than a fact a test settles.

Worth deciding whether step 3 belongs at all. The checklist it prints is manual work a human does
anyway, and `capture-screens.sh` plus the instrumented suite now cover more than they did when this
script was written.
