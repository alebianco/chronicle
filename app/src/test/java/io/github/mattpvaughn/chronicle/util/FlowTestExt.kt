package io.github.mattpvaughn.chronicle.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

/*
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
