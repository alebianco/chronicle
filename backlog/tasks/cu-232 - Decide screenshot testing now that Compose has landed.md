---
id: cu-232
title: Decide screenshot testing now that Compose has landed
status: In Review
assignee: []
created_date: '2026-09-08'
updated_date: '2026-09-10 06:59'
labels:
  - R3
  - testing
milestone: m-2
dependencies: []
priority: medium
ordinal: 122000
---

## Why this exists separately

This is the **one cu-194 question the cu-210 programme did not answer**. Every other candidate in
that survey got an explicit adopt or decline — recorded in cu-210's decline table or landed as
cu-211…cu-220 and cu-229…cu-231 — so cu-194 closes citing cu-210. This one does not, and force-
closing it by citation would have recorded an answer nobody gave.

cu-194 deferred it on purpose: *"Assess after Compose lands, since both tools are far better against
Compose than Views."* Compose has landed, so the precondition is met and the question is live.

## The case for it is specific, not general

[[decision-22]]'s whole argument rests on **four landscape/visibility bugs that unit tests could not
see** — cu-19, cu-68, cu-141, cu-142 — and rule 5 in CLAUDE.md still requires a human to install and
screenshot every migrated screen in both orientations because of them. A JVM screenshot test would
catch that class **in the gate**.

That claim has since been strengthened by two more of the same kind:

- **cu-226**: the account notice painted over every sub-screen's `TopAppBar`, hiding the title and
  the back arrow. The semantics tree was correct throughout — the toolbar was composed, present and
  findable by `onNodeWithText` — so only a *position* assertion could fail. It was found by
  screenshotting the licences screen, months after it shipped.
- **cu-191**: the details progress row rendered **blank** where a length belonged. Every unit test
  passed; the formatter was right and what reached it was not.

So the defect class is live and recurring, and it is precisely the class this tooling exists for.

## What must be decided, not assumed

- **Paparazzi or Roborazzi.** Roborazzi runs under Robolectric, which this project already uses for
  62 test classes; Paparazzi does not need it but has its own Compose/AGP version constraints. The
  existing Robolectric investment is a real argument and should be weighed rather than waved at.
- **Whether golden images are committed**, and what that does to the repository. This is the reason
  to think before adopting: goldens are binary, they churn on any theme or font change, and a gate
  nobody can regenerate cheaply becomes a gate people disable.
- **Which screens**, if adopted. `AccountNoticePlacementTest` shows the cheaper alternative already
  works: a **bounds assertion** caught cu-226 with no golden image at all. Screenshot testing must
  be argued against *that*, not against having no test — otherwise it is being adopted for a job a
  plain assertion already does.
- **Whether it replaces or supplements rule 5.** It must not quietly weaken device verification: a
  JVM render is not a device, and the landscape bugs were found on a real tablet.

## Acceptance Criteria

- [x] **Decline**, recorded as [[decision-28]] with the reasoning and three named conditions that
      would overturn it
- [x] Assessed against all **six** bugs individually, in a table. The finding that decided it:
      **three of the six cannot recur** — cu-68, cu-141 and cu-142 are XML/View-era defects
      (`values-land/integers.xml`, a zero-height `ConstraintLayout`, ViewBinding's lost DataBinding
      evaluation). Verified: `app/src/main/res/` has **no `layout*` directory and no `-land`
      qualifier**. The mechanism is gone, not merely unused
- [x] The other three are already guarded, and cu-191 turned out **not** to be the blank-string
      edge case this ticket anticipated: it rendered `00:00/9:26:42 0%`, wrong text from a pure
      function, which `DetailsProgressTextTest` covers directly
- [x] Weighed against `AccountNoticePlacementTest` explicitly — and it wins on precision, on
      stating intent in the diff, on having no regeneration ritual, and on cost
- [x] Golden-image churn: **moot**, nothing adopted. Recorded as a reason rather than skipped —
      a gate nobody can regenerate cheaply is a gate people disable
- [x] Licences checked anyway: all three candidates are **Apache-2.0** and none needs a cloud
      service, so neither GPLv3 nor [[decision-19]] was the blocker. Worth stating, so a future
      reader does not re-litigate a licence question that was never the issue
- [x] **Rule 5 unchanged**, stated explicitly in the decision
- [x] Nothing to land; this one decided

## Closing notes, 2026-09-09

**A working option was found and declined anyway**, which is the honest framing. Roborazzi 1.74.0
(released 2026-09-08) was probed against this project rather than assessed from documentation: the
plugin applies under AGP 9.4.0 **with `android.newDsl=false`** — the one interaction research could
not confirm, since Roborazzi is written against the new variant API while that flag restores the old
one — and registers all six tasks with `BUILD SUCCESSFUL`. The probe was reverted.

So the decline rests on the six bugs, not on tooling being unavailable.

Two candidates were eliminated on their own merits and are worth recording so they are not
re-surveyed: **Paparazzi** cannot be used here at all — Google publishes a named Gradle 9
incompatibility, its AGP 9 tracking issue is still open, and it requires **Java 21** where this
project is on 17. **AGP's own `com.android.compose.screenshot`** is compatible but renders only
`@Preview` composables statically, so it cannot reach a presenter-driven screen — which after
decision-27 is every screen.

## Notes

Closing status **In Review**: it is a judgement about what the gate should cost and what device
verification is still for.

**A decline is a perfectly good outcome** and should not be treated as failure. The programme's own
precedent is that a measurement which overturns an assumption is the useful part — cu-220's Circuit
measurement was accurate and its recommendation still overturned, and cu-228's dependency removal
changed the APK by zero bytes, which was the finding rather than a disappointment.
