package io.github.mattpvaughn.chronicle.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

/*
 * ## Which to reach for: these helpers, or Turbine
 *
 * Both are declared and both stay. They answer **different questions**, and picking by habit rather
 * than by question is how a test ends up longer and less clear than what it replaced.
 *
 * | the question | reach for |
 * |---|---|
 * | "what value did this settle on?" | [settledValue] / [settledValues] |
 * | "did these events arrive, in this order, and then nothing else?" | Turbine |
 * | "keep this `WhileSubscribed` flow hot while I assert" | [keepCollected] |
 *
 * A `StateFlow` conflates, so asking it for a *sequence* is usually asking the wrong question —
 * intermediate values may never be observable, and a Turbine assertion over one tends to be longer
 * and more brittle than a `settledValue`. Conversely a `SharedFlow` command bus is genuinely a
 * sequence, and expressing "and then nothing else" as an assertion over an accumulated list is what
 * these helpers do awkwardly. `SleepTimerBusTest` is the worked example: it carried 33 lines of
 * hand-rolled recorder to say what `testIn` / `awaitItem` / `expectNoEvents` say directly.
 *
 * ## Turbine's own trap: `expectNoEvents()` means "not yet", not "never"
 *
 * Measured, because it is the assertion a "this must never arrive" test reaches for and it is
 * weaker than it reads. A probe emitted an event behind a `delay` and then called
 * `expectNoEvents()`: **it passed**, with the emission still pending. `advanceUntilIdle()` before
 * it does not help either — it does not drain the emission into Turbine's channel.
 *
 * So a negative assertion needs a **barrier**: await something that must arrive *after* the thing
 * being excluded would have, then assert the absence. `SleepTimerBusTest`'s two feedback-loop tests
 * do this — they drain the flow the event legitimately belongs on before asserting it did not also
 * reach the other one, so the absence is a statement about a delivery that has happened rather than
 * one that has not been given the chance.
 *
 * Without that barrier the old accumulated-list comparison was, on this one point, the **stronger**
 * check — it kept accumulating after a `yield`, where `expectNoEvents()` reads a channel once and
 * returns. So use it only where the emission it excludes would have been synchronous, or put a
 * barrier in front of it. Sabotage-verified either way: reinstating the leak fails the barriered
 * test.
 *
 * ## What Turbine does and does not change about the traps below
 *
 * Measured on 2026-09-08 rather than assumed, because both traps are properties of the coroutine
 * test machinery rather than of these helpers:
 *
 * - **Trap 1 still applies, and it is not only about collectors.** A `backgroundScope` *collector*
 *   of a `SharedFlow` is still not resumed by `advanceUntilIdle` — measured again: the list was
 *   empty after `advanceUntilIdle` and held one item after `yield()`. Turbine's `awaitItem()`
 *   suspends properly and returns the value with neither, so on the reading side the trap is
 *   avoided rather than fixed.
 *
 *   **A *producer* parked on a `SharedFlow` hits it identically**, and that half is easy to miss
 *   because there is no collector in sight to blame. A sender launched on the default
 *   `StandardTestDispatcher` and then `advanceUntilIdle`-ed simply never runs, so a counter it
 *   increments reads 0 — which looks exactly like "the bus blocked the sender" and is really "the
 *   sender was never scheduled". This cost a wrong test and a wrong finding in `SleepTimerBusTest`,
 *   recorded in three documents before it was caught. Measured: the same nine sends give
 *   `sent = 0` under `StandardTestDispatcher`, and `sent = 9` under `UnconfinedTestDispatcher`
 *   **or** a real dispatcher. **If a test's subject is what a producer does, do not run it on the
 *   test dispatcher.**
 * - **Trap 2 still applies, in full, inside a `turbineScope`.** A `turbineScope` collecting an
 *   endless flow inside `runBlocking` **hangs** exactly as before — verified by a probe that had to
 *   be killed at a 240 s timeout. `withTimeoutOrNull` does not rescue it either, because
 *   `runBlocking` blocks the very thread the timeout needs. Use `runTest`, and `testIn(backgroundScope)`.
 *
 * ## Two traps these helpers do not cover
 *
 * Both were found the hard way, and both read as a broken production class when the defect is in
 * the harness. They are recorded here because this is the file you open when a flow test misbehaves.
 *
 * ### 1. `advanceUntilIdle()` does not resume a `backgroundScope` collector of a `SharedFlow`
 *
 * `keepCollected` and `settledValues` below advance the dispatcher on purpose, and that is correct
 * **for a `StateFlow`**: the work is queued on the test dispatcher, and draining it produces the
 * value. A `SharedFlow` emission is not that. The value sits in the buffer and the collector is
 * never resumed, so the collected list stays empty and every assertion about it is vacuous.
 *
 * Seven downloader tests failed this way with zero requests reaching the engine, which reads like a
 * broken downloader and was a broken harness. **`yield()` resumes it; `advanceUntilIdle` does not.**
 * `SleepTimerBusTest` measured this directly — after `publish`, `advanceUntilIdle` left the list
 * empty and the next `yield` produced the value — and its `record`/`settle` pair is the pattern to
 * copy. Note it also awaits `onSubscription` before emitting, because a `replay = 0` bus drops
 * anything published before a collector exists.
 *
 * ### 2. A collector on an endless flow inside `runBlocking` never completes
 *
 * `runBlocking` returns when its body returns, and collecting a flow that never ends is a body that
 * never returns — so the call blocks and the suite hangs rather than failing. There is no timeout to
 * read and no assertion output. Launch such a collector as a `Job` and **cancel it** before the
 * block ends (`KtorDownloaderTest` does this), or await a specific event with a timeout rather than
 * collecting open-endedly.
 *
 * Neither trap applies to a `StateFlow` read through the helpers below, which is why those helpers
 * can advance the dispatcher and return a value.
 */

/**
 * Keeps [flow] collected for the rest of the test **and lets it settle**, so a
 * `stateIn(WhileSubscribed)` has actually produced a value by the time the caller asserts.
 *
 * A ViewModel's `StateFlow`s are shared with `WhileSubscribed`, which means they compute **only
 * while something collects them** — with no collector they sit on the seed value passed to
 * `stateIn` and never consult their sources. That is the same reason the `LiveData` tests this
 * replaces called `observeForever {}` before asserting: a `MediatorLiveData` is cold too.
 *
 * The `advanceUntilIdle` is not optional and is the difference from a bare `launch`. Subscribing
 * only *starts* the upstream; the collector has not run yet when `keepCollected` returns, so a
 * `cacheStatus.value` read on the next line still sees the `stateIn` seed. Under
 * `InstantTaskExecutorRule` an `observeForever` delivered synchronously and hid this distinction.
 *
 * Collected on [TestScope.backgroundScope] so it does not keep `runTest` from finishing.
 */
fun <T> TestScope.keepCollected(flow: Flow<T>) {
  backgroundScope.launch { flow.collect {} }
  advanceUntilIdle()
}

/**
 * The values [flows] settle on, subscribed **together** and then advanced once.
 *
 * Subscribing to each in turn, advancing between them, does not work: `advanceUntilIdle` lets the
 * first flow's `stateIn` reach its computed value, and the second flow's upstream then starts from
 * sources that have already settled, so its own `distinctUntilChanged` can suppress the emission
 * and it is left holding the `stateIn` seed. Two `settledValue` calls in one assertion disagreed
 * with each other for exactly that reason, and which one read the seed depended on call order.
 *
 * The collectors are left running on [TestScope.backgroundScope] — dropping the last subscriber
 * sends a `WhileSubscribed` flow back to its seed, and `backgroundScope` ends with the test.
 */
fun <T> TestScope.settledValues(vararg flows: StateFlow<T>): List<T> {
  val seen = List(flows.size) { mutableListOf<T>() }
  flows.forEachIndexed { i, flow -> backgroundScope.launch { flow.collect { seen[i].add(it) } } }
  advanceUntilIdle()
  return flows.mapIndexed { i, flow -> seen[i].lastOrNull() ?: flow.value }
}

/** Single-flow [settledValues]. */
fun <T> TestScope.settledValue(flow: StateFlow<T>): T = settledValues(flow).single()
