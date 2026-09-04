package io.github.mattpvaughn.chronicle.util

import kotlinx.coroutines.CoroutineExceptionHandler
import timber.log.Timber

/**
 * The `CoroutineExceptionHandler` a ViewModel under test is constructed with (cu-33).
 *
 * A real handler rather than a `mockk`, for two reasons. It is the production shape — `AppModule`
 * provides one that logs and swallows — so a test built on it takes the same failure path the app
 * does. And a relaxed mock would answer the `CoroutineContext.get` that `launch` performs with
 * another mock rather than with the handler itself, so nothing would actually be installed and an
 * exception thrown inside the coroutine would surface as an unrelated failure elsewhere.
 *
 * The returned list records what was caught, so a test can assert a failure was *handled* rather
 * than merely that nothing crashed.
 */
fun recordingExceptionHandler(): Pair<CoroutineExceptionHandler, List<Throwable>> {
  val recorded = mutableListOf<Throwable>()
  val handler =
    CoroutineExceptionHandler { _, e ->
      recorded += e
      Timber.e(e, "Caught unhandled exception in test")
    }
  return handler to recorded
}

/** The common case: a handler whose catches the test does not inspect. */
fun testExceptionHandler(): CoroutineExceptionHandler = recordingExceptionHandler().first
