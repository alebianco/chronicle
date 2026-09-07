---
id: cu-216
title: "A third-party licences page, and drop the Play Services plugin that was meant to build it"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - compliance
  - ui
milestone: m-3
dependencies: 
  - cu-210
priority: medium
---

## Description

Owner ask: generate an in-app third-party licences page for GPLv3 compliance.

**Auditing this found a problem worth fixing first.** The project already declares
`play-services-oss-licenses` **and applies its Gradle plugin** —
`app/build.gradle.kts:9` and `build.gradle.kts:14` — and **nothing uses it.** There is no licences
screen anywhere in the app.

That is not merely dead weight. It is a **Google Play Services** dependency, and **decision-1** puts
sideload/F-Droid/homelab distribution first. F-Droid does not accept GMS dependencies, so the tool
nominally intended for this job is itself a distribution blocker.

So this task does both halves: remove the GMS licences plugin and dependency, and build the page with
a libre tool instead.

## The tool

Either works and both are Apache-2.0:

- **AboutLibraries** — generates metadata at build time and ships Compose UI components, so the
  screen is close to free.
- **Licensee** — a Gradle plugin that validates and exports licence data, leaving the rendering to
  us. Lighter, more control, more work.

Prefer whichever adds fewer transitive dependencies; check both against the GPLv3 and decision-19
bars before choosing, and record the choice.

## What compliance actually needs

Worth being precise, because "a licences page" is vague:

- Every bundled third-party dependency named, with its licence and its full licence text or a link.
- **GPLv3's own obligations** are separate and already met elsewhere — `LICENSE` is in the repo and
  the source is public. This page is about the *dependencies*, not about Chronicle's own licence.
- The **licence headers and `LICENSE` file are on the never-touch list** without owner sign-off. This
  task adds a screen; it does not edit either.

## The thing to get right

**A generated page that silently misses a dependency is worse than none**, because it looks like
diligence. So the page must be generated from the resolved dependency graph rather than
hand-maintained, and the count it renders should be checked against the graph at least once.

## Acceptance Criteria

- [ ] `play-services-oss-licenses`, its plugin and its catalogue entries removed; the F-Droid
      rationale (decision-1) recorded in the closing notes
- [ ] A licences screen reachable from Settings, following the `*Screen` + `*Destination` convention
- [ ] Content **generated from the resolved dependency graph**, not hand-written
- [ ] The rendered dependency count reconciled against `releaseRuntimeClasspath` once, so an
      omission is ruled out rather than assumed
- [ ] Licence text or a working link for each entry
- [ ] Screenshotted on a device in **both orientations**
- [ ] APK delta recorded — removing GMS should shrink it; the new tool may offset that
- [ ] `LICENSE` and every licence header untouched
- [ ] `./verify.sh` green

## Notes

Closing status **In Review**: it is a new user-visible screen and a compliance artefact, so both the
look and the completeness want the owner's eye.
