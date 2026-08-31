package io.github.mattpvaughn.chronicle.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/**
 * Supplies the coroutine dispatchers used across the app.
 *
 * Exists so tests can substitute a deterministic scheduler. Code that hardcodes
 * [Dispatchers.IO] cannot be driven synchronously, which is why the repositories'
 * coroutine behaviour has never been asserted (H5) — a test either sleeps and
 * hopes, or does not test the threading at all.
 */
interface DispatcherProvider {
  /** For disk and network work. */
  val io: CoroutineDispatcher

  /** For touching UI state. */
  val main: CoroutineDispatcher

  /** For CPU-bound work such as sorting a large library. */
  val default: CoroutineDispatcher
}

/** Production implementation, delegating to the real [Dispatchers]. */
class DefaultDispatcherProvider
  @Inject
  constructor() : DispatcherProvider {
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val default: CoroutineDispatcher get() = Dispatchers.Default
  }
