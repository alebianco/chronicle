---
id: cu-192
title: Drop the unused media3-ui dependency
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - debt
dependencies: []
priority: low
milestone: m-2
---

## Description

Found during the 2026-09-06 backlog and docs cleanup, and independently recorded by
[[decision-22]] while measuring the Compose POC:

> **`PlayerView` appears nowhere in the app, and `androidx.media3.ui` is imported by no Kotlin
> file.** The player screen is TextViews, ImageViews, a Slider and a RecyclerView — an audiobook
> player has no video surface. `media3-ui` is a declared dependency that the code does not use.

Verified 2026-09-06: `implementation(libs.media3.ui)` at `app/build.gradle.kts:229`, declared in
`gradle/libs.versions.toml:86`, and **zero** `androidx.media3.ui` imports under `app/src`.

Same shape as cu-167 (`kotlin-reflect` / `moshi-kotlin`), which measured a 218 KB APK saving from
dropping one unused artifact. This one is smaller but free.

## The thing to get right

`media3-ui` is the artifact whose absence R8 would not warn about, so **measure rather than
assume**: record the `releaseRuntimeClasspath` diff and the APK size before and after, as cu-167
did. Run `./test_release_build.sh` — Media3 classes are reflection-adjacent and the ProGuard rules
are deliberately narrow (cu-45).

Note this is a *dependency* removal, not a capability decision: Google Cast (cu-168) uses
`media3-cast`, not `media3-ui`, and the notification is drawn by the system from
`NotificationCompat`. Confirm neither is affected before removing.

## Acceptance Criteria

- [ ] `media3-ui` removed from `app/build.gradle.kts` and the version catalogue
- [ ] `./verify.sh` green, including the release-compile stage
- [ ] `./test_release_build.sh` passes its dex assertions
- [ ] APK size delta recorded in the closing notes
- [ ] Confirmed that Cast (cu-168) and the media notification are unaffected
