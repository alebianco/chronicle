---
id: cu-221
title: "The instrumented suite regressed while nothing was running it"
status: In Review
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

**Resolved.** `LoggedInLaunchTest` failed two of three tests on every device for about six days. It
now passes on the `api35` managed device and on the owner's tablet.

**The cause was not what this ticket first said, twice over.** Both wrong diagnoses are kept, because
each was stated confidently and the correction is the useful part:

1. *"Nothing seeds a login state."* **False.** `MockPlexMode.enable` seeds the account token, server
   and library, then calls `determineLoginState()` — and logcat confirmed
   `Login event changed to LOGGED_IN_FULLY`, with books syncing from the fixture. The session was
   fine all along. That claim came from grepping `androidTest/` for `authToken` and finding nothing,
   without checking that the seeding lived in `debug/` instead.
2. *"It is a race."* **Also false, though it looked compelling** — `LOGGED_IN_FULLY` genuinely
   arrived 2.4 s after the first test started. But an explicit `waitUntil` then timed out after
   **30 seconds**, which disproved it: the node was never going to appear.

**What it actually was.** A `uiautomator` dump of the running app showed `content-desc` listing
`Library`, `Search` and `Settings` — and no `Home`. The selected tab exposes its label as `text`
instead. `NavigationBarItem` with `alwaysShowLabel = false` renders a `Text` for the selected item
only, and Compose merges that into the item's semantics, replacing the icon's `contentDescription`.
Home is the launch destination, so it is always selected, so its description is always gone.

The test had been right when written and broke when the app started landing on Home. **Dumping the
screen would have found this in minutes; reading code found two plausible wrong answers first.**

## What changed

- `LoggedInLaunchTest` matches the tab by content description **or** visible text, narrowed with
  `hasClickAction()` because "Home" also appears as a shelf heading.
- `awaitHomeShelf()` waits for the shell before asserting. The race was not the cause, but it is
  real — 2.4 s on a warm tablet, and a cold CI emulator is slower.
- The underlying accessibility defect is **cu-223**. This matcher is a workaround for it.

## Acceptance Criteria

- [x] The cause is measured, not guessed — found by dumping the screen after two code-reading
      diagnoses proved wrong
- [x] `LoggedInLaunchTest` passes on the `api35` managed device — 3/3
- [x] It also passes on `api27`, or the reason it cannot is recorded — api27 cannot start on a CI
      runner at all (cu-222); locally it is covered by `instrumentedCheckGroup`
- [x] Whatever seeds the session lives beside `setMockPlexEnabled` — it already did, in
      `MockPlexMode.enable`; this criterion was written on a false premise
- [x] **Sabotage-verified**: reverting the matcher to content-description-only fails on api35;
      restored in a separate call and green again
- [x] `AutoBrowseTreeTest` is run too — it is in the 10 that pass on api35 in CI, so it was never
      broken
- [x] `./verify.sh --instrumented` green
- [x] The fix was small; the underlying accessibility defect is split out as cu-223

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
