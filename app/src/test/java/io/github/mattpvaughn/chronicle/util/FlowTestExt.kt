package io.github.mattpvaughn.chronicle.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

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
