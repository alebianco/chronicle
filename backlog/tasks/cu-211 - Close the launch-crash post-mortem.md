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

## Part 1 — a launch-smoke test

`./verify.sh` cannot catch a defect in DI wiring that only runs on a real Android runtime, and should
not be expected to. What is missing is one cheap instrumented test that does what a human does:
install, launch, look.

`ChronicleTestRunner` already exists as the instrumentation runner.

**Where it runs is a real decision, not a detail.** An instrumented test needs hardware, so it cannot
join the eight stages without making the inner loop require a device. Either a `verify.sh --device`
stage run before closing any task that touched DI or startup, or a CI emulator job. Prefer whichever
the owner will actually run — a gate that is too slow gets disabled, which is worse than none.

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

- [ ] An instrumented test launches the app and fails if the process dies during startup
- [ ] It asserts a rendered Home shelf, not merely that no exception was thrown
- [ ] **Sabotage-verified against the real defect**: reverting `PLACEHOLDER_URL` to its slash-less
      form makes it fail. If it does not, it does not do its job
- [ ] It runs in mock mode with no real Plex credentials
- [ ] Where it runs is decided and written down — a stage, a CI job, or both
- [ ] The rule is in `reference/00-constitution.md`'s testing section, **with the base-url crash as
      its worked example** — the concrete story is what makes a rule stick
- [ ] The audit above is carried out and its result recorded, including "nothing else found"
- [ ] Anything found is fixed, or has a recorded reason it is legitimate
- [ ] `./verify.sh` green

## Notes

Closing status **In Review**: the judgement about *where* the smoke test runs is a workflow choice
the owner should see.

First in cu-210's programme, and cu-210's own criteria say the rest is unjustifiable until this
lands — a programme of toolchain bumps is exactly the situation where a repeat of this blocker is
most likely.
