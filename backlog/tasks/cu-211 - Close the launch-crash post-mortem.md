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
- [x] `ci.yml` runs the instrumented suite on pull requests, on its own `instrumented` job, and its
      triggers include `feature/agentic-dev` (cu-212 added the branch)
- [ ] **Confirmed from the run log that an emulator actually booted and tests executed** — KVM must
      be enabled on the runner. A job that reports green having silently skipped the suite is the
      precise failure this task exists to prevent
- [x] `--instrumented` stays **off** by default locally; the inner loop is unchanged
- [x] **Sabotage-verified against the real defect, on hardware**: with `PLACEHOLDER_URL` reverted
      to its slash-less form, the run dies with `RuntimeException: Unable to create application
      ... IllegalStateException: Base URL needs to end with /` and **0 tests execute**. Restored in a
      separate call; 3 tests then start
- [ ] `LoggedInLaunchTest` is confirmed to still assert a rendered Home shelf, not merely the absence
      of an exception; extended if it does not
- [x] It runs in mock mode with no real Plex credentials — `mockPlexModeIsActive` passes
- [ ] The measured wall-clock cost of the PR job is recorded in the closing notes — the owner accepted
      it on PRs, and the number is what makes that reviewable

**The rule that failed**
- [x] The Definition of Done gains it as step 3: run `./verify.sh --instrumented` before moving a
      task to `In Review` or `Done`
- [x] `reference/00-constitution.md`'s testing section gains **a test may not disable a check that
      production performs**, with the base-url crash as its worked example
- [x] The audit for the same shape elsewhere is carried out — **nothing else found**, recorded
      below
- [x] Anything found is fixed, or has a recorded reason it is legitimate — the two hits are both
      legitimate, reasoned below
- [ ] `./verify.sh` green, and `--instrumented` green at least once locally

## The audit result (2026-09-07)

Searched for the general shape — a test switching off something production performs.

| Pattern | Found | Verdict |
|---|---|---|
| `checkUrl = false` | 0 outside the guard | The original defect; gone, and `BaseUrlContractTest` fails if it returns |
| `expectSuccess` | 3 references in `KtorErrors.kt` | **Sets it to `true`**, matching production rather than relaxing it |
| `allowMainThreadQueries()` | 9 files | All on `inMemoryDatabaseBuilder` test databases. Production builds through the real builder and is unaffected — the standard Room test idiom, not a disabled check |
| `@Config` lowering the SDK below `minSdk` | 0 | — |
| `relaxed = true` mocks standing in for the contract under test | 0 of 48 | 48 files use relaxed mocks, but none mocks `PlexService`, a Ktorfit instance or `HttpClient` — the shape that hid the base-url crash |

**Nothing else found.** Recorded explicitly because "we looked and found nothing" is a different
statement from "we did not look", and only one of them is worth anything later.

## Notes

Closing status **In Review**. The gate is wired and sabotage-proved on hardware, but two criteria
need a real Actions run — the KVM/emulator-boot confirmation and the measured PR cost — and one
finding below is the owner's call on sequencing.

**Wiring the gate immediately found the suite is red.** `LoggedInLaunchTest` fails two of three tests
on the `api35` managed device *and* on the tablet: the app launches fine but is not signed in, so it
renders onboarding and there is no nav bar to assert on. It was green on 2026-09-01 and the Ktor
migration landed in between. Filed as **cu-221**.

That is this task's own thesis arriving on schedule — the suite rotted *because* nothing ran it — but
it has a sequencing consequence: **turning the PR job on before cu-221 lands makes the next pull
request red for a pre-existing reason.** Two defensible orders, and the owner should pick:

1. **Land cu-221 first, then this.** CI goes green from its first run. Costs a little time.
2. **Land this now and accept a red tick** until cu-221 fixes it. The red is honest and is precisely
   the signal that has been missing for six days.

The sabotage check is worth keeping in mind for whoever does cu-221: reverting `PLACEHOLDER_URL`
crashes the process before any test runs, which is a *different* failure from these assertion
failures. If a future run shows 0 tests started rather than 2 failed, the base URL is the suspect.

**One measurement not yet taken.** The PR job's wall-clock cost. It cannot be measured from a
worktree, and the ruling that put this on every PR deserves a real number attached to it.
