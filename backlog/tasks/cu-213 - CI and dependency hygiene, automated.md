---
id: cu-213
title: "CI and dependency hygiene, automated"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - tooling
  - trust
milestone: m-3
dependencies:
  - cu-210
priority: medium
---

## Description

Three pieces of automation that all live in CI, all cost nothing to run, and all replace work this
project has done by hand. Grouped because they are one afternoon of configuration, not three
projects.

**None of them changes app code**, which is why this can proceed while cu-195 still has open device
criteria.

## 1. Dependabot — what is newer

No dependency-update automation exists. cu-210's programme is largely version bumps, and the fact
that **Room's KMP support landed in 2.8.3 while this project sits on 2.8.1** was found by reading
release notes by hand — exactly what Dependabot surfaces for free.

**It must target `feature/agentic-dev`.** decision-23 puts all task work there until a release is
cut, because `develop` has neither the backlog nor `verify.sh`. Dependabot defaults to the default
branch, so `target-branch` is explicit — **and `ci.yml`'s triggers must gain `feature/agentic-dev`,
which today they lack.** Without that, its PRs open against a branch CI does not test.

**Ignore the deliberate pins, and cite why**, or a weekly PR against each trains everyone to ignore
the bot:

| Pin | Reason | Remove the ignore when |
|---|---|---|
| Kotlin, KSP | no KSP release for Kotlin 2.4 | KSP ships for 2.4 |
| Ktorfit ≥ 2.7 | needs kotlin-stdlib 2.4.0 | Kotlin moves (cu-216) |
| Compose BOM > 2026.06.x | needs compileSdk 37 (decision-22) | cu-216 stage 3 lands |
| `lifecycle-*` > 2.10.0 | needs compileSdk 37 **and** AGP 9.1 | cu-216 stage 3 lands |
| Room 3.0 | breaking major, alpha, no consumer | cu-182 names a target |
| `hamcrest` 1.3 | 2.2 resolves the wrong version for Espresso (cu-54) | probably never |

CI already runs `./verify.sh` on pull requests, so **every Dependabot PR gets the full eight-stage
gate** — a far stronger signal than a bump PR usually gets.

## 2. CodeQL — security analysis, inside GitHub

Owner ask: a Sonar-like platform, free, ideally from GitHub. **Code scanning with CodeQL is free for
public repositories**, supports Kotlin natively (set the language to `java`), and ships
Android-specific queries.

It fits the constraints where a hosted platform would not: analysis runs in Actions and results stay
in the repository. **decision-19** forbids data extraction and **decision-12 rule 7** already rejects
coverage SaaS — the same reasoning rejects a SaaS quality platform.

Kotlin is a compiled language for CodeQL, so the workflow builds the project and needs the JDK and
Gradle cache setup `ci.yml` already has.

**A scanner that reports nothing is indistinguishable from one that is not running**, so this must be
confirmed by seeing it analyse Kotlin sources, not by a green tick.

For later: if Sonar-style debt *tracking* is wanted once a homelab exists, the options satisfying
decision-19 are **self-hosted Sonar CE** or **`mobsfscan`**. Recorded so it is not researched twice.

## 3. Dependency Analysis plugin — what is unused

Twice now unused dependencies have been found by hand audit: cu-167 (`kotlin-reflect`,
`moshi-kotlin` — 218 KB) and cu-192 (`media3-ui`, `facebook-infer-annotation`, `work-testing` —
290 KB). This is the other half of Dependabot: **newer versus unused.**

**The trap, pre-declared:** `hamcrest-modern` looks unused — no `org.hamcrest` import in the three
`androidTest` files — but Espresso's `ViewMatchers` reference `org.hamcrest.Matchers` **at runtime**,
and `hamcrest-all:1.3` alone resolves the wrong version. cu-54 established this. A first report that
confidently recommends removing it teaches everyone to distrust the tool, so the exception goes in
**before** the first run. Same care for Room, Hilt, Moshi and Ktorfit processors, and the Media3
classes whose ProGuard rules are deliberately narrow (cu-45).

## Acceptance Criteria

**Dependabot**
- [ ] `.github/dependabot.yml` covers `gradle` and `github-actions`, weekly
- [ ] `target-branch: feature/agentic-dev`, and that branch added to `ci.yml`'s `push` and
      `pull_request` triggers
- [ ] Every pin above ignored with its reason and unblocking condition in a comment
- [ ] Related updates **grouped** — Kotlin with KSP, each AndroidX family together
- [ ] Verified by observation: at least one PR has opened against the right branch and been checked
      by CI. A config that has never produced a PR is not known to work

**CodeQL**
- [ ] Runs on push and pull request for the CI branches, `feature/agentic-dev` included
- [ ] Confirmed by the run log that it **analysed Kotlin sources**
- [ ] Every finding triaged — fixed, or dismissed with a reason. An untriaged alert backlog is the
      same as no scanner
- [ ] Nothing leaves GitHub; no third-party account created (decision-19)

**Dependency analysis**
- [ ] Applied, with `hamcrest-modern` pre-declared as runtime-only, citing cu-54
- [ ] First report triaged in full: acted on, or recorded as a deliberate exception
- [ ] Anything removed is measured — `releaseRuntimeClasspath` diff and APK delta, as cu-167 and
      cu-192 both did
- [ ] `./test_release_build.sh` passes after any removal — unused-looking deps are often
      reflection-reached
- [ ] `./verify.sh` green

## Notes

Closing status **In Review**: the observed-PR criterion and the triage judgements need the owner.

The dependency-analysis part is the lowest-value third — it automates work already done twice, so the
remaining unused surface is probably small. Its value is preventing the *next* accumulation. If time
is short, Dependabot and CodeQL are the halves worth having.
