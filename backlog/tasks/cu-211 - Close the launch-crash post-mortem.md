---
id: cu-211
title: "Close the launch-crash post-mortem"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - testing
  - trust
milestone: m-3
dependencies:
  - cu-210
  - cu-212
priority: high
---

## Description

**The Ktor migration shipped a 100% launch crash, and 1,678 unit tests were green.** Both halves of
that need closing: the missing check, and the reason the suite could not see it.

`PLACEHOLDER_URL` had no trailing slash. Ktorfit's `baseUrl` validates for one and throws. The
clients are `@Singleton`, so Hilt built them inside `Application.onCreate` — no window was ever
created, and the user bounced to the launcher with no crash dialog.

**Why it escaped:** four tests built Ktorfit instances and every one passed `checkUrl = false`, to
accommodate a `FakePlexServer.url` that trims its trailing slash for `PlexConfig`'s benefit. The
suite switched off precisely the validation that fires in production. The missing slash was one
character; **the disabled check is the actual defect.**

`BaseUrlContractTest` already guards that specific instance. This task covers the general cases.

## Part 1 — run the launch-smoke test that already exists

**The test was already written. Nothing ever ran it.**

`LoggedInLaunchTest` launches `MainActivity` through `createAndroidComposeRule` in mock-Plex mode and
asserts the Home nav item renders. The base-url `IllegalStateException` fires inside
`Application.onCreate`, so the activity could not have started and that test could not have passed.
It would have caught this crash on the day it was written.

It never executed. `verify.sh` has an `instrumentedCheckGroup` stage, but it sits behind an opt-in
`--instrumented` flag, and `ci.yml` has no instrumented job at all. So the defect did not escape
because a check was missing — **it escaped because the instrumented suite is never run by anything.**
That reframes this task: the work is wiring and a rule, not writing a test.

The infrastructure is further along than first assumed. Gradle Managed Devices already declare
`api27` (the minSdk floor) and `api35`, grouped as `instrumentedCheckGroup`, provisioned by Gradle
rather than a CI-runner action — so the same command works locally and on any forge (D12 rule 6).

### The owner's ruling (2026-09-07)

Asked where it should run, given the task's own warning that *"a gate that is too slow gets disabled,
which is worse than none"*:

> `--instrumented` should run on PRs, as an opt-in locally, with a matching rule to do it when a task
> is ready to review.

Three parts, all three required:

1. **On pull requests** — CI runs it, so it cannot be forgotten.
2. **Opt-in locally** — `--instrumented` stays off by default; the inner loop stays fast.
3. **A rule at the review boundary** — run it before moving a task to `In Review` or `Done`. The
   Definition of Done is where that belongs, because that is the checklist actually consulted at the
   moment the rule applies.

Note `ci.yml` today triggers only on `main`, `master` and `develop` — not `feature/agentic-dev`,
where decision-23 puts all task work. **cu-212 fixes that trigger**, so this task's CI half depends
on it, or must add the branch itself. An instrumented job on a branch CI does not build is not a
gate.

Emulators need KVM. GitHub's `ubuntu-latest` runners support it, but it must be enabled deliberately
— confirm the job actually boots an emulator rather than reporting green having skipped it. **A
green run that ran nothing is the exact failure this whole task exists to prevent.**

## Part 2 — the rule that failed

> **A test may not disable, relax or stub a check that production performs.** If a fixture cannot
> satisfy a production constraint, fix the fixture — do not switch off the constraint.

The constitution's testing section is the place: it already carries *"Sabotage-verify every guard"*
and the mock-vs-fake rule, which are the same family of idea.

**Audit for the same shape elsewhere** — the value is knowing whether this was one mistake or a
habit:

- `expectSuccess`, `checkUrl`, `validateEagerly`-style flags on any client or builder
- `allowMainThreadQueries()` on Room — legitimate in a test, but confirm no production behaviour
  depends on the difference
- any `@Config` lowering an SDK level below `minSdk`
- `relaxed = true` mocks standing in for a collaborator whose contract is the thing under test

Deliberately **not** a lint rule. The general form is not mechanically detectable, and the specific
instance is already guarded.

## Acceptance Criteria

**The gate — it must be proved to run, not merely configured**
- [ ] `ci.yml` runs `./verify.sh --instrumented` on pull requests, and its triggers include
      `feature/agentic-dev` (or cu-212 has added it first)
- [ ] **Confirmed from the run log that an emulator actually booted and tests executed** — KVM must
      be enabled on the runner. A job that reports green having silently skipped the suite is the
      precise failure this task exists to prevent
- [ ] `--instrumented` stays **off** by default locally; the inner loop is unchanged
- [ ] **Sabotage-verified against the real defect**: with `PLACEHOLDER_URL` reverted to its
      slash-less form, the instrumented run fails. If it does not, the gate does not do its job
- [ ] `LoggedInLaunchTest` is confirmed to still assert a rendered Home shelf, not merely the absence
      of an exception; extended if it does not
- [ ] It runs in mock mode with no real Plex credentials
- [ ] The measured wall-clock cost of the PR job is recorded in the closing notes — the owner accepted
      it on PRs, and the number is what makes that reviewable

**The rule that failed**
- [ ] The Definition of Done gains: run `./verify.sh --instrumented` before moving a task to
      `In Review` or `Done`. It goes there, not in the testing section, because the DoD is the
      checklist read at the moment the rule applies
- [ ] `reference/00-constitution.md`'s testing section gains **a test may not disable, relax or stub
      a check that production performs**, with the base-url crash as its worked example
- [ ] The audit for the same shape elsewhere is carried out and its result recorded, including
      "nothing else found"
- [ ] Anything found is fixed, or has a recorded reason it is legitimate
- [ ] `./verify.sh` green, and `--instrumented` green at least once locally

## Notes

Closing status **In Review**. The where-it-runs question is now answered (recorded in Part 1), but
the owner should see the measured PR cost — a gate accepted in principle can still turn out too slow
in practice, and that is a judgement, not a measurement.

**Depends on cu-212** for the `feature/agentic-dev` CI trigger, or must add it here. An instrumented
job on a branch CI never builds is not a gate.

First in cu-210's programme, and cu-210's own criteria say the rest is unjustifiable until this
lands — a programme of toolchain bumps is exactly the situation where a repeat of this blocker is
most likely. cu-214's AGP 9 step in particular is the one change that can break startup in a way no
unit test sees.

**The premise changed once already.** The original ticket asked for a launch-smoke test to be
written; investigation found `LoggedInLaunchTest` already does that job and simply never runs. If
something similar surfaces while working this — a check that exists but is unwired — prefer wiring
it to writing a second one.
