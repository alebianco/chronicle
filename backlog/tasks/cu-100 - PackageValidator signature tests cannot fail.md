---
id: cu-100
title: PackageValidator signature tests cannot fail
status: Done
assignee: [claude]
created_date: '2026-09-01'
labels: [R1, agentic, security]
dependencies: []
priority: high
milestone: m-1
---

## Description

`PackageValidatorSignatureTest` has 8 passing tests and `PackageValidator` sits at **0% coverage,
702 missed instructions**. The tests exercise private helpers *re-implemented inside the test file*;
production `isKnownCaller` is never invoked.

The file is candid about this and asks review to keep the mirrors in step — an earlier version was
already called "documentation-not-coverage" in review. But the project rule is
**a check that cannot fail proves nothing**, and this guards *fail-open* allowlist logic in an
**exported** `MediaBrowserService`: both branches historically admitted a caller they should not
have (cu-61).

The stated blocker — the real decision needs a `Context`, a `PackageManager` and a parsed XML
resource — no longer holds. Robolectric 4.16.1 is already on the classpath and used in nine test
files, including `RoomMigrationTest`.

- `app/src/test/.../util/PackageValidatorSignatureTest.kt:31, :45` — the mirrored helpers
- `app/src/main/.../util/PackageValidator.kt` — the untested production decision

## Acceptance Criteria

- [x] `isKnownCaller` is exercised directly under Robolectric with a fake `PackageManager`.
- [x] Both fail-open regressions are covered: a null platform signature must not admit an unsigned
      caller; a whitelisted app with an unpinned signature must be refused, not crash.
- [x] Each new test verified by deliberate sabotage — restore the original expression and watch it
      fail.
- [x] The mirrored private helpers are deleted once the real ones are covered.
- [x] Coverage baseline updated; `PackageValidator` no longer reads 0%.

## Implementation Notes

`PackageValidatorSignatureTest` deleted; `PackageValidatorTest` added, driving the real
`isKnownCaller` under Robolectric with `ShadowPackageManager.installPackage`. 7 tests covering both
fail-open rules, the two permission allowances, the self-uid case and cache behaviour.

**A production bug fell out of it that the mirrors could not have found.** `getPackageInfo` is
declared `PackageInfo?`, but `PackageManager.getPackageInfo` *throws* `NameNotFoundException` for an
unknown package — so every `?:` fallback below it was unreachable, including the one in
`getSystemSignature` written specifically to tolerate a missing platform package rather than take
the service down. The constructor threw instead. It now catches and returns null, which is what the
signature always claimed.

**A trap worth recording.** The first version of the fixture set only `PackageInfo.signatures`. At
SDK 28+ `getSignature` reads `signingInfo` instead, and `robolectric.properties` pins SDK 34 — so
the caller signature was null, which short-circuits `callerSignature != null &&` and the allowlist
branch was never reached. A deliberate sabotage of that branch **passed**. Fixed with
`ShadowSigningInfo.setSignatures`; the sabotage then failed with the expected
`NoSuchElementException`.

Coverage 20.84% -> 21.98% from this task alone.
