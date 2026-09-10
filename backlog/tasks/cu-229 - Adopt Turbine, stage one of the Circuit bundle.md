---
id: cu-229
title: 'Adopt Turbine, stage one of the Circuit bundle'
status: In Review
assignee: []
created_date: '2026-09-08'
updated_date: '2026-09-10 06:59'
labels:
  - R3
  - testing
  - architecture
milestone: m-2
dependencies: []
priority: medium
ordinal: 119000
---

## Why this is first

[[decision-26]] adopts Circuit, Molecule and Turbine as one bundle, staged so that a stall leaves
nothing half-migrated. Turbine goes first because it is **purely additive**: it changes no
architecture, touches no navigation, and is useful on its own. It also proves the dependency is
acceptable — licence, [[decision-19]] — at the smallest possible stake.

**Latest is `app.cash.turbine:turbine:1.2.1`** (checked 2026-09-08).

## What it does and does not replace

`util/FlowTestExt.kt` — `keepCollected`, `settledValue`, `settledValues` — is used by **4 test
suites** and was written for this codebase's specific traps. **It does not retire wholesale**, and
assuming it does is the main way this task could go wrong:

- **Turbine replaces stream assertions**: "these events arrived, in this order, and then nothing
  else". That is what `awaitItem` / `expectNoEvents` are for, and it is what the current helpers
  express awkwardly.
- **`settledValue` solves a different problem** — `StateFlow` conflation, where the question is
  "what value did this settle on", not "what sequence arrived". Turbine has no better answer for
  that, and rewriting those assertions in Turbine would make them longer and less clear.

So the deliverable is **Turbine used where the assertion is genuinely stream-shaped**, not a sweep.

## The two traps that must survive

Both are already in `FlowTestExt.kt`'s KDoc and cost real debugging time. They are properties of the
coroutine test machinery, not of the helpers, so **Turbine does not fix either** and both still apply
inside a `turbineScope`:

- **`advanceUntilIdle()` does not resume a `backgroundScope` collector of a `SharedFlow`.** Seven
  downloader tests once failed with zero requests reaching the engine — which reads like a broken
  downloader and was a broken harness. `yield()` works.
- **A collector on an endless flow inside `runBlocking` never completes**, so `runBlocking` never
  returns and the suite hangs until the collector is cancelled.

## Acceptance Criteria

- [x] `app.cash.turbine:turbine` declared as a **test** dependency, version pinned in the catalogue
      at **1.2.1** (confirmed latest against Maven Central rather than taken from this ticket)
- [x] `SleepTimerBusTest` converted — genuinely stream-shaped, and it carried a **33-line
      hand-rolled recorder** with two documented traps that Turbine removes. Before and after below
- [x] **A test written that the old harness could not express** — and it was wrong on the first
      attempt, in a way worth recording; see below
- [x] `FlowTestExt`'s KDoc updated with a which-to-reach-for table, and the measured trap findings
- [x] Both traps verified under Turbine **by probe**, and the KDoc corrected where the answer
      differed from the assumption
- [x] Test-only: **0 occurrences** on `releaseRuntimeClasspath`, and `buildHealth` does not flag it
      as unused (i.e. it is genuinely used). Also absent from the generated licences catalogue,
      which is built from the release classpath — correct, since it never ships
- [x] `./verify.sh` green, 10 stages
- [x] Licence **Apache 2.0**, read from the published POM. GPLv3-compatible

## The conversion, before and after

```kotlin
// before — 33 lines of `record`/`settle` harness above this, plus:
val commands = record(bus.commands)
repeat(3) { bus.publish(SleepTimerUpdate(remainingMillis = 0L, isActive = true)) }
settle()
assertEquals("a tick reached the command flow", emptyList<SleepTimerCommand>(), commands)

// after
turbineScope {
  val commands = bus.commands.testIn(backgroundScope)
  repeat(3) { bus.publish(SleepTimerUpdate(remainingMillis = 0L, isActive = true)) }
  commands.expectNoEvents()
}
```

**The honest measurement.** The file *grew*, 226 → 302 lines, because three tests and a good deal of
KDoc were added. Stripping comments and blanks, code went 142 → 182 across 9 → 12 tests — **15.8 →
15.2 code lines per test**, which is near enough flat.

So the win is **not brevity**, and claiming it would be would be false. It is that 33 lines of
hand-rolled harness carrying two documented traps are gone, and that "and then nothing else" is now
a first-class assertion (`expectNoEvents`) rather than a comparison against an accumulated list that
happened to be complete when it was read.

Sabotage-verified: reinstating the feedback loop this suite exists to prevent — a tick also emitted
on the command flow — fails the converted test.

## The test the old harness could not write

**`a command sent with no subscriber is dropped rather than blocking the caller`.**

`SleepTimerBus.command`'s KDoc already documented the behaviour — `emit` on a `replay = 0`
`SharedFlow` with no subscriber discards the value and returns — and named a live hazard with it:
*"a new entry point that could set a timer with the service down — Android Auto, a widget, a
shortcut — would silently drop the command"*. Nothing asserted the shape of that, so a later "fix"
making `command` suspend (a `subscriptionCount` guard, say) would deadlock those callers with no
test objecting. Sabotage-verified with exactly that guard: the test fails.

**This test was wrong first, and the correction is the more useful record.** The first version
asserted the *opposite* — that a command with no subscriber waits rather than being dropped — on the
strength of a measurement showing `sent = 0`. That measurement was of the **test harness, not the
bus**: a producer parked on a `SharedFlow` is not resumed by `advanceUntilIdle` under
`StandardTestDispatcher`, so the sender coroutine never ran. That is **trap 1**, from the very file
this task also updates, hit on the producer side where there is no collector in sight to blame.

It could not have failed, either: a bus that dropped silently gives `sent = 0` too, so the assertion
distinguished nothing — the "check that cannot fail" class. Caught by self-review, then measured
three ways:

| dispatcher | result |
|---|---|
| `StandardTestDispatcher` (as written) | `sent = 0`, job never completes |
| `UnconfinedTestDispatcher(testScheduler)` | `sent = 9`, completes |
| a real dispatcher via `runBlocking` | `sent = 9`, completes immediately |

So the test now runs on a **real dispatcher**, deliberately, and `FlowTestExt`'s trap 1 note is
corrected to say the trap is not only about collectors.

## Turbine's own trap, found on the way

**`expectNoEvents()` means "nothing has arrived *yet*", not "nothing ever will."** Measured: it
passes with an emission still pending behind a `delay`, and `advanceUntilIdle()` before it does not
help. For a "this must never arrive" assertion that is **weaker** than the accumulated-list
comparison it replaced, which at least kept accumulating.

The two feedback-loop tests therefore drain the flow the event legitimately belongs on *before*
asserting it did not also reach the other, so the absence is a statement about a delivery that has
happened rather than one that has not been given the chance. Recorded in `FlowTestExt` so the next
reader does not have to rediscover it.

## The traps, re-measured

Both are properties of the coroutine test machinery, so they were probed rather than assumed:

- **Trap 1 still applies, and it is not only about collectors.** On the reading side: a
  `backgroundScope` collector held **0** items after `advanceUntilIdle` and **1** after `yield()`,
  while Turbine's `awaitItem()` returns the value with neither — so there the trap is *avoided*
  rather than fixed. On the **producing** side it bites identically and is far easier to miss, since
  no collector is in sight to blame; that is what made this task's flagship test wrong. The
  `FlowTestExt` note is corrected to say so.
- **Trap 2 still applies in full.** A `turbineScope` collecting an endless flow inside `runBlocking`
  **hangs** — the probe had to be killed at a 240 s timeout, and `withTimeoutOrNull` does not rescue
  it because `runBlocking` blocks the very thread the timeout needs. The KDoc now says so.

## What was deliberately *not* converted

`KtorDownloaderTest` has a second hand-rolled recorder and looks like the obvious next candidate. It
stays, with the reason recorded in its KDoc:

- It runs on **real dispatchers with real file I/O**, so event order is not deterministic. Its
  assertion is "an event matching this predicate eventually arrived"; `awaitItem()` asserts the
  *next* item, a stronger claim than the suite can honestly make — the conversion would turn a
  correct test into a flaky one.
- It is `runBlocking`, which is trap 2 territory.

`PlayBookGuardsTest` and `PauseFlushesProgressTest` only construct `PlaybackErrorBus` as a
collaborator and make no stream assertions, so there is nothing to convert.

`FlowTestExt` **does not retire**. `settledValue` answers "what did this settle on" for a conflated
`StateFlow`, which Turbine has no better answer for, and `keepCollected` keeps a `WhileSubscribed`
flow hot. The KDoc now carries a table saying which to reach for.

## Notes

**Do not convert all 4 suites for the sake of it.** A conversion that makes an assertion longer is
evidence the helper was the right tool, and recording that is a useful result — the same way cu-220's
measurement was useful even though its recommendation was overturned.
