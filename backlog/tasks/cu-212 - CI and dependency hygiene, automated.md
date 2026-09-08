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
- [x] Confirmed by the run log that it **analysed Kotlin sources** — run 34139943708:
      `Successfully loaded extractor Java/Kotlin (java)`, analysis completed, conclusion success
      — **not met, and deliberately left unticked.** This needs a real Actions run, which requires
      the branch pushed. The task itself says a scanner that reports nothing is indistinguishable
      from one that is not running, so a green config is not evidence.
- [x] Every finding triaged — **the first scan produced zero security alerts**, confirmed against
      the API rather than inferred from a green tick (see below) — **blocked on the first run above.**
- [x] Nothing leaves GitHub; no third-party account created (decision-19)

**Dependency analysis** — **adopted, advisory only.** Was blocked on a pre-existing defect; see cu-225.
- [x] Applied, with `hamcrest-modern` pre-declared as runtime-only, citing cu-54 — and
      confirmed the tool reports it as unused anyway, which is why this is advisory not a gate
      — configuration was written and the exception pre-declared as the task required, then backed
      out: `buildHealth` analyses *every* variant, so it compiles `releaseUnitTest`, which has
      never compiled in this repo (`MoveSyncLocationHookTest` calls a `DebugHooks` member that
      exists only in the debug source set). Reproduced on a clean checkout with no plugin applied,
      so it is not caused by this change. `ignoreSourceSet` filters advice but not the task graph.
- [x] First report triaged in full — produced once cu-225 unblocked it; triage table above
- [x] Anything removed is measured — **nothing was removed.** Acting on the findings is a
      separate task; three genuinely-unused dependencies are named above for it
- [x] `./test_release_build.sh` passes after any removal — not applicable, no removal
- [x] `./verify.sh` green — 8/8 stages when this task's CI work landed, in the task worktree. The
      gate has since grown to **10** (detekt, then the release unit-test stage), and was re-run
      green at 10/10 when dependency-analysis was added.

## CI evidence (2026-09-07)

**CodeQL ran and passed** — run `34139943708`, `Analyze (java-kotlin)`, ~4 minutes, loading the
Java/Kotlin extractor and completing its analysis. Nothing left GitHub (decision-19 satisfied).

**Zero security alerts, and that was verified rather than assumed.** Five analyses have been ingested,
each reporting `results=1` while the alerts endpoint returns an empty list — the discrepancy is a
diagnostic result, not a hidden finding. Checked all states, not just `open`.

The scan is real, not vacuous: `compileDebugKotlin` **executed** during the traced build (45 tasks,
none up to date), so Kotlin was compiled under the extractor. That distinction matters here — a
CodeQL job whose build is cached extracts nothing and still reports success, which is the same shape
as the launch-crash trap.

Reading this needed the `alebianco` token: `gh` is globally authenticated as a work account with only
`pull` on this repo. No scope refresh was required — the owner's stored token already works. See the
`.envrc` note below.

**Per-repo GitHub auth.** `gh auth switch` is global and would change every other project, so this
repo carries an uncommitted `.envrc` exporting `GH_TOKEN` from gh's keyring for the `alebianco`
account. direnv scopes it to this directory. It is gitignored: it names one person's account and is a
property of a machine, not the project.

**Dependabot has not run and will not yet.** It reads its config from the **default branch**, which
on this fork is `develop` — and decision-23 defers touching `develop` until a release is cut. So the
"observed PR" criterion is blocked by a deliberate branching decision, not by anything missing here.
Either wait for the release, or place `dependabot.yml` on `develop` alone as an exception. That is a
call for the owner; it is recorded rather than silently worked around.

## Notes

Closing status **In Review**: three criteria need a real Actions run the worktree cannot produce —
an observed Dependabot PR, CodeQL's run log, and its first triage.

**Assembled from two agent attempts, because neither was complete alone.** The first added
Dependabot, CodeQL and the `ci.yml` trigger; QA rejected it over two real pin defects. The rework
fixed those but was handed a *fresh* worktree, could not see the first attempt, and rewrote the
config from scratch — losing CodeQL. The landed commit takes the first attempt's breadth plus the
rework's fixes and its `DependabotPinTest` guard.

**Three pin defects, all found by review rather than by a green build:**

1. `org.hamcrest:hamcrest-all` alone left `org.hamcrest:hamcrest:2.2` free to move, and the pair is
   deliberately unbalanced (cu-54). Now `org.hamcrest:*`.
2. An `update-types` filter on Kotlin/KSP let *patch* bumps through, which still move kotlin-stdlib
   past the KSP ceiling. Filter dropped.
3. `>2.10.0` on lifecycle blocked a harmless 2.10.x patch; the constraint belongs to 2.11.0.

**The guard test took three attempts, and the first two passed against a real sabotage** — worth
recording, because both looked reasonable. A `notes >= entries / 2` ratio passed because a surplus of
comments in one block pays for a pin documented nowhere. Attributing each pin to the comment block
above it also passed: the sabotage sat *inside* an already-documented group, directly beneath the
Room entry, and inherited its note. No comment-scanning rule separates "covered by the comment
above" from "slipped in beneath it". The assertion is now an explicit roster of the ten ignored
coordinates, so adding a pin means declaring it where a reviewer sees it.

All three defects are sabotage-verified: each reintroduced, the guard fails, restored in a separate
call per the Gradle up-to-date trap.

**The dependency-analysis third is now built** (2026-09-08), once cu-225 fixed the pre-existing
defect it was blocked on — the release unit-test variant had never compiled. It is applied
**advisory only**: `./gradlew buildHealth` on demand, `severity("warn")` for every issue type, and
deliberately **not** a `verify.sh` stage.

**Two traps found wiring it up, both of which produce a false clean bill of health:**

- **Applied at the root only, `buildHealth` succeeds and reports nothing** — literally *"No project
  health reports found"*, with exit code 0. It must be applied to `:app` as well. A green build with
  an empty report is indistinguishable from a green build with a clean one.
- **Version 2.19.0 fails on this toolchain**: `Provided Metadata instance has version 2.4.0, while
  maximum supported version is 2.2.0`, because Kotlin 2.3.21 emits metadata the plugin's bundled
  `kotlin-metadata-jvm` cannot read. It also warns it is only known to work with AGP 8.3.0–8.10.0.
  **3.19.1 works** on AGP 8.13.2 and Kotlin 2.3.21, with neither the failure nor the warning.

**Why advisory and not a gate.** The first report proves the point — it lists 17 unused
dependencies, and several are correct declarations the tool cannot see:

| Reported | Verdict |
|---|---|
| `hamcrest-modern` | **Correct as declared** — runtime-only, cu-54. No source imports it by design |
| `ktorfit-lib`, `work`, `room-ktx`, `lifecycle-*-ktx` | **Keep** — these are umbrella artifacts whose APIs are used through transitive modules the tool would have us declare individually. 75 files import each family |
| `constraintlayout`, `coordinatorlayout`, `interpolator` | **Genuinely unused** — zero references in `.kt` or `.xml`. Residue of the View system; the last layout is gone |
| `recyclerview`, `fragment` | **Genuinely reachable still** — 1 and 4 references respectively |

The "should be declared directly" half (60+ entries) is mostly the tool asking us to pin every
transitive Compose, lifecycle and DataStore module by hand, which trades a real maintenance cost for
a theoretical correctness one. Not adopted.

**Acting on the findings is deliberately a separate task**, not this one: removing a dependency is
release-build risk (`./test_release_build.sh`, R8, reflection) and each removal needs its own
verification. The criterion here is that the report exists and has been triaged — it has.
