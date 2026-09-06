---
id: cu-192
title: Drop the unused declared dependencies
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

## Two more found the same way (2026-09-06)

A follow-up sweep of every `[libraries]` alias against real imports found two others:

- **`work-testing`** (`libs.versions.toml:95`, `app/build.gradle.kts:260`) — **zero imports of
  `androidx.work.testing` anywhere.** The only mention of `TestListenableWorkerBuilder` in the tree
  is inside a *comment* in `WorkerDispatcherTest`. `ChronicleWorkerFactory.kt:35-37` documents this
  in the source itself. **Do not simply delete it** — cu-179 is the task that finally uses it, so
  the right outcome is "keep, and cu-179 makes it real" or "drop and re-add in cu-179". Decide
  deliberately rather than by sweep.
- **`facebook-infer-annotation`** (`libs.versions.toml:143`, `app/build.gradle.kts:199`,
  `compileOnly`) — zero `com.facebook.infer` imports. Almost certainly inherited from upstream.
  `compileOnly`, so it is not in the APK and the win is catalogue hygiene, not size.

**Checked and *not* unused:** `hamcrest-modern` looks unused (no `org.hamcrest` import in the three
`androidTest` files) but is a deliberate cu-54 fix — Espresso's `ViewMatchers` reference
`org.hamcrest.Matchers` *at runtime*, and `hamcrest-all:1.3` alone resolves the wrong version.
`app/build.gradle.kts:271-276` explains it. **Leave it alone**; a sweep keyed on imports would
wrongly flag it, which is the trap this note exists to prevent.

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
- [ ] `facebook-infer-annotation` removed (or its purpose recorded if it turns out to have one)
- [ ] `work-testing` decided deliberately in light of cu-179, not swept
- [ ] `hamcrest-modern` left in place — verified as a runtime-only Espresso need (cu-54)
- [ ] `./verify.sh` green, including the release-compile stage
- [ ] `./test_release_build.sh` passes its dex assertions
- [ ] APK size delta recorded in the closing notes
- [ ] Confirmed that Cast (cu-168) and the media notification are unaffected
