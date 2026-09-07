---
name: chronicle-commit-before-optimising
description: Commit characterisation tests before changing the code they pin, or a scripted edit can delete the untracked test
metadata:
  type: feedback
---

When writing tests that characterise existing behaviour before an optimisation, **commit the tests
first**, while they still pass against the old implementation. Then change the code.

Two things went wrong in one session on 2026-09-04 without this: a scripted edit whose `assert`
failed left the source untouched but I read the result as applied, and an untracked test file was
lost entirely — never committed, so `git` could not recover it and it had to be rewritten from
scratch.

Committing first also makes the sabotage step meaningful: the tests are known-good against the old
code, so a failure after the change is information rather than ambiguity between "the fix is wrong"
and "the test is wrong".

**Why:** an untracked file has no safety net, and a green run against new code proves nothing if the
test never ran against the old code.
**How to apply:** write tests → run them green against current behaviour → **commit** → change the
implementation → run again → sabotage-verify. Also prefer `Edit` over scripted `python3` string
replacement for source edits; a failed assertion in a script is silent about having changed nothing.

Related: [[chronicle-sabotage-rerun-tasks]]
