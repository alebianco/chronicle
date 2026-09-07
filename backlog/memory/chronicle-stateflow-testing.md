---
name: chronicle-stateflow-testing
description: Testing a stateIn(WhileSubscribed) flow needs a subscriber AND a drained dispatcher; subscribing to two one at a time makes the second read its seed
metadata:
  type: project
---

Chronicle's ViewModels expose `stateIn(viewModelScope, WhileSubscribed(5_000), seed)`. In a unit
test `.value` is the **seed** unless two things are true: something is collecting, and the test
dispatcher has been drained. `MainDispatcherRule` installs a `StandardTestDispatcher`, which queues
rather than runs, so `advanceUntilIdle()` is required after subscribing.

The trap that cost the most time: subscribing to two flows **one at a time** does not work. The
first `advanceUntilIdle` lets flow A settle, and flow B's upstream then starts from sources that
have already emitted, so B's own `distinctUntilChanged` suppresses the emission and B is left on
its seed. Which one reads the seed depends on call order, so the failure looks nondeterministic.

`util/FlowTestExt.kt` (test source set) has the helpers: `keepCollected`, `settledValue`, and
`settledValues` — use `settledValues` whenever one assertion compares two flows.

Related: [[chronicle-commit-before-optimising]], [[chronicle-sabotage-rerun-tasks]].
