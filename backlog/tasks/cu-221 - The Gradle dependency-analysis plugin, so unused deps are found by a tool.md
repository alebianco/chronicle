---
id: cu-221
title: "The Gradle dependency-analysis plugin, so unused deps are found by a tool"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - tooling
  - debt
milestone: m-3
dependencies: 
  - cu-210
  - cu-213
priority: low
---

## Description

Twice now, unused dependencies have been found by **hand audit**: cu-167 (`kotlin-reflect`,
`moshi-kotlin` — 218 KB of APK) and cu-192 (`media3-ui`, `facebook-infer-annotation`, `work-testing`
— 290 KB). cu-194 notes the plugin *"would have found `media3-ui` and cu-167's `kotlin-reflect`
automatically instead of by audit. Cheap and self-justifying if it works."*

It pairs with cu-213: **Dependabot reports what is newer, this reports what is unused.** Between them
the two halves of dependency hygiene stop being manual.

## The trap this must not fall into

**An import-keyed sweep gives wrong answers, and this repo has the counter-example written down.**
`hamcrest-modern` looks unused — no `org.hamcrest` import in the `androidTest` sources — but
Espresso's `ViewMatchers` reference `org.hamcrest.Matchers` **at runtime**, and `hamcrest-all:1.3`
alone resolves the wrong version. cu-54 established this and `app/build.gradle.kts` explains it in a
comment.

So the plugin must be configured with that exception **pre-declared**, before its first report,
because a first run that confidently recommends removing `hamcrest-modern` teaches everyone to
distrust it.

Same care for anything reflection-adjacent: Room, Hilt, Moshi and Ktorfit processors, and the Media3
classes whose ProGuard rules are deliberately narrow (cu-45).

## Acceptance Criteria

- [ ] The plugin applied, and its report runs
- [ ] `hamcrest-modern` pre-declared as a known runtime-only dependency, citing cu-54
- [ ] The first report is **triaged in full**: every finding either acted on or recorded as a
      deliberate exception with a reason. An untriaged report is noise
- [ ] Anything actually removed is measured — `releaseRuntimeClasspath` diff and APK delta, as
      cu-167 and cu-192 both did
- [ ] `./test_release_build.sh` passes after any removal, since unused-looking deps are often
      reflection-reached
- [ ] Whether it becomes a `verify.sh` stage or a periodic manual run is decided and recorded
- [ ] `./verify.sh` green

## Notes

Deliberately **low** priority. It automates work already done twice by hand, so the remaining unused
surface is probably small — its value is preventing the *next* accumulation, not clearing a backlog.

Closing status **Done** unless it recommends removing something load-bearing, in which case the
judgement belongs to the owner.
