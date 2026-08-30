---
id: cu-55
title: Fix test_release_build.sh APK path for unsigned release
status: Draft
assignee: []
created_date: '2026-08-30'
labels: [R0, release]
dependencies: []
priority: low
---

## Description

Found incidentally while running the release smoke test during cu-1. Pre-existing since `2a5cc3d`,
unrelated to that task.

`test_release_build.sh:20` expects `app/build/outputs/apk/release/app-release.apk`, but there is no
release signing config (deliberately — signing is owner-only per CLAUDE.md), so R8 emits
`app-release-unsigned.apk`. The script therefore always prints "❌ APK not found" **after a fully
successful R8 build**, and exits non-zero.

That makes the release gate a false negative: a real R8/ProGuard failure and a healthy build look the
same to anyone reading the exit code. Given that the whole point of cu-3 was removing checks that lie,
this is worth correcting.

Fix: accept either filename, or glob `app/build/outputs/apk/release/*.apk`, and report which variant
was produced. Also verify the script's other assertions still hold.

## Acceptance Criteria

- [ ] `./test_release_build.sh` exits 0 on a successful unsigned release build
- [ ] Script still fails loudly when R8/ProGuard genuinely breaks
- [ ] Signing remains untouched (owner-only)
