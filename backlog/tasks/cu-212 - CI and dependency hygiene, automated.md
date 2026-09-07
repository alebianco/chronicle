---
id: cu-212
title: "CI and dependency hygiene, automated"
status: In Review
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
| Ktorfit ≥ 2.7 | needs kotlin-stdlib 2.4.0 | Kotlin moves (cu-214) |
| Compose BOM > 2026.06.x | needs compileSdk 37 (decision-22) | cu-214 stage 3 lands |
| `lifecycle-*` > 2.10.0 | needs compileSdk 37 **and** AGP 9.1 | cu-214 stage 3 lands |
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
- [x] `.github/dependabot.yml` covers `gradle` and `github-actions`, weekly
- [x] `target-branch: feature/agentic-dev`, and that branch added to `ci.yml`'s `push` and
      `pull_request` triggers
- [x] Every pin above ignored with its reason and unblocking condition in a comment
- [x] Related updates **grouped** — Kotlin with KSP, each AndroidX family together
- [ ] Verified by observation: at least one PR has opened against the right branch and been checked
      by CI. A config that has never produced a PR is not known to work
      — **not met.** Dependabot only reads the config once it is on the default branch at GitHub;
      nothing observable can happen from an unmerged worktree. Owner check after merge.

**CodeQL**
- [x] Runs on push and pull request for the CI branches, `feature/agentic-dev` included
- [ ] Confirmed by the run log that it **analysed Kotlin sources**
      — **not met, and deliberately left unticked.** This needs a real Actions run, which requires
      the branch pushed. The task itself says a scanner that reports nothing is indistinguishable
      from one that is not running, so a green config is not evidence.
- [ ] Every finding triaged — fixed, or dismissed with a reason. An untriaged alert backlog is the
      same as no scanner — **blocked on the first run above.**
- [x] Nothing leaves GitHub; no third-party account created (decision-19)

**Dependency analysis** — **not adopted; blocked on a pre-existing defect.** See draft-221.
- [ ] Applied, with `hamcrest-modern` pre-declared as runtime-only, citing cu-54
      — configuration was written and the exception pre-declared as the task required, then backed
      out: `buildHealth` analyses *every* variant, so it compiles `releaseUnitTest`, which has
      never compiled in this repo (`MoveSyncLocationHookTest` calls a `DebugHooks` member that
      exists only in the debug source set). Reproduced on a clean checkout with no plugin applied,
      so it is not caused by this change. `ignoreSourceSet` filters advice but not the task graph.
- [ ] First report triaged in full — **no report can be produced until draft-221 is fixed.**
- [ ] Anything removed is measured — nothing was removed.
- [ ] `./test_release_build.sh` passes after any removal — not applicable, no removal.
- [x] `./verify.sh` green — 8/8 stages, run in the task worktree.

## Notes

Closing status **In Review**: the observed-PR criterion and the triage judgements need the owner.

The dependency-analysis part is the lowest-value third — it automates work already done twice, so the
remaining unused surface is probably small. Its value is preventing the *next* accumulation. If time
is short, Dependabot and CodeQL are the halves worth having.

## Closing notes (2026-09-07)

Shipped **the two halves the task itself named as the ones worth having**: Dependabot and CodeQL.
The dependency-analysis third is not adopted, and that is the one judgement here worth the owner's
eye.

**Why the third was dropped rather than forced.** Applying the plugin surfaced a real defect:
`compileReleaseUnitTestKotlin` has never succeeded in this repository. Confirmed against a clean
checkout with the plugin fully reverted, so the plugin exposed it rather than caused it. Two ways
around it were available and both rejected — modifying app code (the task states three times that
none of its parts changes app code, and moving test files carries its own verification), or
configuring the analysis to depend on the breakage persisting, which is the "scanner that reports
nothing" failure this task explicitly warns against. Filed as draft-221 with three options and a
recommendation instead.

**What no agent can tick here.** Both remaining Dependabot and CodeQL criteria need the branch
pushed and a real Actions run — Dependabot does not read its config from an unmerged worktree, and
CodeQL's "confirmed it analysed Kotlin sources" is by design not satisfiable by a config file.
Ticking either from a green `verify.sh` would be exactly the substitution the task warns about.

**Verified:** `./verify.sh` green, 8/8 stages. `dependabot.yml` and `codeql.yml` parse, and all six
documented pins resolve to coordinates that exist in `libs.versions.toml`. No app code was touched —
the diff is two workflow files, one Dependabot config, one doc section, and one draft.
