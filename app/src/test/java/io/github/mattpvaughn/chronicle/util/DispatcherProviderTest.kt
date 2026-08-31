package io.github.mattpvaughn.chronicle.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DispatcherProviderTest {
  @Test
  fun `default provider maps to the real dispatchers`() {
    val provider = DefaultDispatcherProvider()

    assertSame(Dispatchers.IO, provider.io)
    assertSame(Dispatchers.Main, provider.main)
    assertSame(Dispatchers.Default, provider.default)
  }

  /**
   * The point of the abstraction: a test provider must actually redirect work
   * away from the real IO pool, otherwise injecting it buys nothing.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `test provider runs io work on the test scheduler`() =
    runTest {
      val scheduler = TestCoroutineScheduler()
      val provider = TestDispatcherProvider(scheduler)

      var ranOn = ""
      withContext(provider.io) {
        ranOn = Thread.currentThread().name
      }

      assertEquals(
        "io work must not land on a real IO pool thread",
        false,
        ranOn.startsWith("DefaultDispatcher-worker"),
      )
    }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `test provider routes every dispatcher to one scheduler`() {
    val scheduler = TestCoroutineScheduler()
    val provider = TestDispatcherProvider(scheduler)

    // Sharing one scheduler is what keeps virtual time consistent across a test
    // that hops between io and main.
    assertSame(provider.io, provider.main)
    assertSame(provider.main, provider.default)
  }
}
