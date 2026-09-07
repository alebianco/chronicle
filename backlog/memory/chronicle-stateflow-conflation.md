---
name: chronicle-stateflow-conflation
description: A StateFlow conflates equal consecutive values where a LiveData transformation re-emitted — null→null silently drops, and a collector driving UI visibility never fires
metadata:
  type: project
---

`StateFlow` only emits when the value **changes**. A `LiveData` `map` re-emitted whenever its source
did, regardless. That difference is invisible until two consecutive emissions are equal.

It bit for real when cu-151 (written in LiveData) merged into cu-52's migration: the rules tester
drove its headline from `winningRule`, and testing one unparseable title after another produced
`null → null`. The collector never fired, so the headline stayed hidden while the list beneath it
updated correctly. Neither branch could have caught it — the bug existed only in the merged tree.

**How to apply:** when a collector drives *visibility* or a one-line summary, drive it from a flow
that genuinely changes with the input (the list, the query) rather than from a derived flow whose
value is often the same. And when converting a `LiveData` `map` to `stateIn`, ask whether
consecutive equal values are meaningful — if they are, the conflation is a behaviour change.

Testing note: asserting "two different inputs produce different derived values" can be *false* —
two failing titles yield identical `PatternAttempt` lists. Assert the reported state after each
input instead.

Related: [[chronicle-stateflow-testing]], [[chronicle-device-check-catches-wiring]].
