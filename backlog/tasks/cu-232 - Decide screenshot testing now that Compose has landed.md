---
id: cu-232
title: "Decide screenshot testing now that Compose has landed"
status: To Do
assignee: []
created_date: '2026-09-08'
labels:
  - R3
  - testing
milestone: m-3
dependencies: []
priority: medium
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

- [ ] An explicit adopt or decline, with reasoning, and for a decline what would change the answer
- [ ] Assessed specifically against the **six** known bugs of this class (cu-19, cu-68, cu-141,
      cu-142, cu-226, cu-191), naming which ones a screenshot test would actually have caught —
      cu-191 is a live test of that, since a blank string may or may not be visible in a diff
- [ ] Weighed against `AccountNoticePlacementTest`'s bounds-assertion approach, which caught one of
      them with no golden image. If bounds assertions cover the class, that is the honest answer
- [ ] Golden-image storage and churn addressed if adopted, including who regenerates them and how
- [ ] Licence checked and compatible with GPLv3, and [[decision-19]] respected (no cloud service,
      no data leaving the machine)
- [ ] Rule 5's status stated either way — supplemented or unchanged, never silently weakened
- [ ] Any adoption lands as its own task; this one decides

## Notes

Closing status **In Review**: it is a judgement about what the gate should cost and what device
verification is still for.

**A decline is a perfectly good outcome** and should not be treated as failure. The programme's own
precedent is that a measurement which overturns an assumption is the useful part — cu-220's Circuit
measurement was accurate and its recommendation still overturned, and cu-228's dependency removal
changed the APK by zero bytes, which was the finding rather than a disappointment.
