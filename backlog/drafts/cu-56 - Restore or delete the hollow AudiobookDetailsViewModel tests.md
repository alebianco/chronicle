---
id: cu-56
title: Restore or delete the hollow AudiobookDetailsViewModel tests
status: Draft
assignee: []
created_date: '2026-08-30'
labels: [R1, agentic]
dependencies: []
priority: high
---

## Description

Found while evaluating mutation testing. `AudiobookDetailsViewModelTest` contains **5 `@Test` methods
whose bodies are entirely commented out** — not just the assertions, the whole body. They call no
production code and assert nothing, so they pass unconditionally and can never fail.

Dormant since `6ef9787` (v0.42.1).

Why this matters beyond tidiness:

- They are 5 of the suite's 19 tests. Any "19 tests passing" claim overstates real verification by ~26%.
- They execute `AudiobookDetailsViewModel` construction in `setUp`, so they contribute *coverage*
  while verifying nothing — the exact failure mode the cu-3 ratchet cannot detect, since coverage
  measures execution, not assertion.
- This is precisely the class of problem mutation testing exists to surface.

Two honest options:

1. **Restore them** — uncomment, fix against the current `AudiobookDetailsViewModel` API (they predate
   several refactors, so expect compile errors and changed collaborators), and confirm each fails when
   the behaviour under test is broken.
2. **Delete them** — if restoring is more work than writing fresh tests, delete and let cu-44 cover
   this ViewModel properly. A deleted test is honest; a hollow one is not.

Prefer (1) if the intent is still valid — the commented bodies document what was meant to be verified
(transport controls called on play, jump-to-chapter, cache-button state machine). Prefer (2) if the API
has moved too far.

Either way the outcome must be that no test in the repo passes unconditionally. Note deleting them will
*lower* the coverage baseline; that is a correct, deliberate drop — use `./coverage-ratchet.sh --update`
and say so in the commit message.

## Acceptance Criteria

- [ ] No `@Test` method in the repo has a fully commented-out body
- [ ] Any retained test verified to fail when the behaviour it covers is broken
- [ ] Coverage baseline adjusted deliberately if tests are removed
