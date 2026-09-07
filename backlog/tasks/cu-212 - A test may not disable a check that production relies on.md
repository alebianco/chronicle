---
id: cu-212
title: "A test may not disable a check that production relies on"
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

The other half of the launch-crash post-mortem, and the more general lesson.

`PLACEHOLDER_URL` was missing a trailing slash. Ktorfit's `baseUrl` validates for one and throws.
**Four tests built Ktorfit instances and every one passed `checkUrl = false`** — to accommodate a
`FakePlexServer.url` that deliberately trims its trailing slash for `PlexConfig`'s benefit.

So the suite switched off *precisely* the validation that fires in production, and then reported
green. The missing slash was one character; **the disabled check is the actual defect.**

`BaseUrlContractTest` now guards that specific instance, including a rule that fails if any test
reintroduces `checkUrl = false`. This task is about the pattern, because the next one will not be
about base URLs.

## The rule to write down

> **A test may not disable, relax or stub a check that production performs.** If a fixture cannot
> satisfy a production constraint, fix the fixture — do not switch off the constraint. A test that
> opts out of a production check is not testing production.

The constitution's testing section is the place: it already carries
*"Sabotage-verify every guard"* and the mock-vs-fake rule, which are the same family of idea.

## Where else this shape may already exist

Worth an audit as part of this task rather than a guess — the search is for tests that pass a flag
production does not, or relax a validation:

- `expectSuccess`, `checkUrl`, `validateEagerly`-style flags on any client or builder
- `allowMainThreadQueries()` on Room — legitimate in a test, but worth confirming no *production*
  behaviour depends on the difference
- any `@Config` that lowers an SDK level below `minSdk`
- `relaxed = true` mocks standing in for a collaborator whose contract is the thing under test

The audit's value is not in finding more instances; it is in knowing whether this was one mistake or
a habit.

## Acceptance Criteria

- [ ] The rule is written in `reference/00-constitution.md`'s testing section, with the base-url
      crash as its worked example — the concrete story is what makes a rule stick
- [ ] The audit above is carried out and its result recorded, including "nothing else found" if that
      is the answer
- [ ] Any instance found is either fixed or has a recorded reason it is legitimate
- [ ] `./verify.sh` green

## Notes

Deliberately **not** a lint rule or a guard test. The general form ("a test disables a production
check") is not mechanically detectable, and a guard that only catches `checkUrl = false` already
exists in `BaseUrlContractTest`. This is a written convention, which is the honest tool for it.

Closing status **Done**: a documentation change with an audit result attached, no user-visible
surface.
