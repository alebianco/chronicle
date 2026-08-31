package io.github.mattpvaughn.chronicle.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Routes every dispatcher to a single [TestCoroutineScheduler] so tests run on one
 * virtual clock.
 *
 * Sharing the scheduler matters: code that hops from [io] to [main] would otherwise
 * advance two independent clocks, and `advanceUntilIdle()` would leave half the work
 * pending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestDispatcherProvider(
  scheduler: TestCoroutineScheduler = TestCoroutineScheduler(),
) : DispatcherProvider {
  private val dispatcher = UnconfinedTestDispatcher(scheduler)

  override val io: CoroutineDispatcher = dispatcher
  override val main: CoroutineDispatcher = dispatcher
  override val default: CoroutineDispatcher = dispatcher
}
