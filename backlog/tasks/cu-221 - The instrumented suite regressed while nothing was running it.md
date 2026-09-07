---
id: cu-221
title: "The instrumented suite regressed while nothing was running it"
status: To Do
assignee: []
created_date: '2026-09-07'
labels:
  - R3
  - testing
  - trust
milestone: m-3
dependencies:
  - cu-211
priority: high
---

## Description

**`LoggedInLaunchTest` is red, and has been for about six days.** Two of its three tests fail on the
managed `api35` device and on the owner's tablet, with the same assertion:

```
Assert failed: The component with ContentDescription = 'Home' is not displayed!
  LoggedInLaunchTest > launchesIntoTheAppWhenAlreadySignedIn  FAILED
  LoggedInLaunchTest > survivesRecreation                     FAILED
```

`mockPlexModeIsActive` passes, so the fixture server is up. The app starts — there is no crash. It
simply is not logged in, so it renders onboarding, which has no bottom nav.

**The cause, as far as it was measured.** `ChronicleTestRunner` calls `DebugHooks.setMockPlexEnabled`,
and that function sets exactly one boolean — `KEY_MOCK_PLEX` — and seeds nothing else. Nothing
anywhere under `app/src/androidTest/` writes an auth token or a login state (`grep` for
`LOGGED_IN_FULLY` and `authToken` returns nothing). The test's own KDoc says it depends on "a seeded
session", and calls that "the precondition every other case rests on" — but the seeding it describes
is not there. Whether it was lost or never existed outside a fixture that has since changed is the
first thing to establish.

**It was green on 2026-09-01**, in `c6dac579 "get the instrumented suite running green on a managed
device"`. Between then and now the whole Ktor migration landed — including `30fdfecf`, the launch
crash fix, which changed how `PlexConfig` and the login repo are wired. That is the window.

**This is cu-211's thesis, demonstrated.** The suite rotted *because* nothing ran it. Wiring the gate
without fixing this means the first PR after cu-211 lands goes red for a reason that predates it.

## Acceptance Criteria

- [ ] The cause is measured, not guessed — what seeded the login state when this was green, and what
      stopped doing so
- [ ] `LoggedInLaunchTest` passes on the `api35` managed device
- [ ] It also passes on `api27`, the minSdk floor, or the reason it cannot is recorded
- [ ] Whatever seeds the session lives beside `setMockPlexEnabled` rather than being duplicated in
      the test, so it cannot drift silently — the same argument that KDoc already makes for the mock
      flag
- [ ] **Sabotage-verified**: with the seeding removed, the test fails again
- [ ] `AutoBrowseTreeTest` is run too, and its state recorded — it is in the same suite and has had
      the same amount of nothing running it
- [ ] `./verify.sh --instrumented` green
- [ ] If the fix is not small, it is split and this task tracks the split

## Notes

Closing status **In Review** if the seeding involves a product-shaped choice about what a "signed-in"
fixture user has; **Done** if it is purely mechanical.

**Do not fix this by weakening the assertion.** Asserting something less specific than "the nav bar
rendered" would make the test pass while removing the only thing it checks — and the constitution's
rule about tests that disable production checks was written for exactly this temptation, in this
same file's history.

Found while wiring cu-211's CI job: the sabotage check (revert `PLACEHOLDER_URL` to its slash-less
form) worked perfectly — the process crashed with `IllegalStateException: Base URL needs to end with
/` and 0 tests ran — but restoring it revealed these two failures underneath.
