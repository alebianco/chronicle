---
id: cu-194
title: Decide on the deferred Compose ecosystem libraries
status: To Do
assignee: []
created_date: '2026-09-06'
labels:
  - R2
  - architecture
dependencies:
  - cu-187
  - cu-188
  - cu-185
priority: low
milestone: m-2
ordinal: 63000
---

## Description

cu-181 surveyed the Compose ecosystem and deferred four candidates with *"evaluate only if the POC
succeeds"*. The POC did succeed and [[decision-22]] was accepted, but only **Hilt** got a task
(cu-185). The rest were never filed, so they exist as an unanswered question in a closed task's
prose — exactly the shape of thing that gets lost.

This task closes them out. **Sequenced last in R2 deliberately**: three of the four can only be
judged against a codebase whose screens are already Compose, so it depends on cu-187, cu-188 and
cu-185.

## What is actually still open

Two of cu-181's four are already settled and need no decision — recorded here so nobody re-opens
them:

- **Coil-Compose** — *adopted*. `libs.coil.compose` is declared and used (`app/build.gradle.kts:152`).
  It was the easy one: same Coil 3 the app already used, so Compose loads cover art through the
  same image loader rather than a second stack.
- **Material3** — *adopted*. `libs.compose.material3` at `app/build.gradle.kts:145`.
  Note **`material3-adaptive` is a different artifact and is not declared.** That one belongs to
  cu-28 (adaptive layouts, R3), not here — it is a layout feature, not an architecture choice.

So the real question is two libraries:

### Circuit (slackhq) — Apache-2.0

cu-181's own note: *"Compose-only, so it is a non-starter today and a genuine option once screens
are Compose."* After cu-188 that precondition is met.

The argument **for** is that its model — screen = state + events, UI is a pure function of state —
is what `StateFlow` + `collectWhileStarted` already approximates here, made explicit and testable.
The argument **against** is that we would be replacing a working pattern that the team (and the
agents) already know, and Circuit brings its own navigation, which collides with the Navigation
Compose assumption baked into decision-22 and cu-188.

**The honest default is no.** Principle 3 prefers a boring maintained dependency over bespoke code
— but that applies to code we would otherwise *write*, and here we would be *replacing* code that
works. Adopting it needs a concrete defect it fixes, not architectural preference.

### Molecule (cashapp) — Apache-2.0

Turns a `@Composable` into a `StateFlow`. Genuinely useful where presentation logic is complex
enough that composing it beats combining flows by hand.

The question to answer with evidence: **do we have such a place?** `combineDistinct`
(`util/FlowCombinators.kt`) plus the `stateIn` sharing-policy rules already cover what this codebase
does. If no ViewModel is actually painful, the answer is no and it should be recorded as such.

## The thing to get right

**Decline is a valid, and probably the expected, outcome.** The point of this task is that the
deferral gets an explicit answer with reasoning, not that something gets adopted. Two of the four
already turned out to be "yes, and it already happened"; the other two may well be "no, and here is
why" — which is worth writing down so cu-181's prose stops being an open loop.

Any adoption must clear the standing bars: licence compatible with GPLv3 (both are Apache-2.0, so
fine), no data extraction ([[decision-19]] — neither has a network path, so also fine), and it must
not force the compileSdk 37 / AGP 9.1 jump that [[decision-22]] records as the reason the Compose
BOM is pinned at the 2026.06.x line.

## Acceptance Criteria

- [ ] Circuit: adopt or decline, with the reasoning recorded — and if declined, what would change
      the answer
- [ ] Molecule: adopt or decline, evidenced by naming a ViewModel it would actually simplify (or
      recording that none is painful enough)
- [ ] Confirmed that neither forces compileSdk 37 or AGP 9.1
- [ ] cu-181's deferred list is fully resolved — Coil-Compose and Material3 noted as already
      adopted, `material3-adaptive` handed to cu-28
- [ ] Outcome recorded as an ADR if either is adopted; the task file is enough if both are declined
