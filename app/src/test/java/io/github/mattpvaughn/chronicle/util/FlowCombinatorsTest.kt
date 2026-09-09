package io.github.mattpvaughn.chronicle.util

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two properties every `combineDistinct` arity has to have.
 *
 * **The dedup is not an optimisation.** `FlowCombinators`' own header says so: it is the per-tick
 * Room invalidation fix, and a combinator that re-emitted an identical value would put the cost
 * straight back. So "emits only when the result changes" is asserted, not assumed.
 *
 * The five-source overload is the one that most needs this. It cannot use `combine`'s typed form —
 * there is no arity-5 overload — so it goes through the vararg form and casts positionally. A
 * mis-ordered cast would compile and hand the combiner the wrong values, which is exactly the class
 * of mistake a test can catch and a reviewer cannot.
 */
class FlowCombinatorsTest {
  @Test
  fun `five sources reach the combiner in declaration order`() =
    runTest {
      val a = MutableStateFlow("a")
      val b = MutableStateFlow(2)
      val c = MutableStateFlow(3.0)
      val d = MutableStateFlow(true)
      val e = MutableStateFlow('e')

      combineDistinct(a, b, c, d, e) { v1, v2, v3, v4, v5 -> "$v1|$v2|$v3|$v4|$v5" }.test {
        // Positional, on purpose: five distinct *types* would let the compiler catch a swap, so
        // the assertion has to pin the order the values actually arrive in.
        assertEquals("a|2|3.0|true|e", awaitItem())
        cancelAndIgnoreRemainingEvents()
      }
    }

  @Test
  fun `five sources emit again when any one of them changes`() =
    runTest {
      val a = MutableStateFlow(1)
      val b = MutableStateFlow(1)
      val c = MutableStateFlow(1)
      val d = MutableStateFlow(1)
      val e = MutableStateFlow(1)

      combineDistinct(a, b, c, d, e) { v1, v2, v3, v4, v5 -> v1 + v2 + v3 + v4 + v5 }.test {
        assertEquals(5, awaitItem())

        // The last source, since a positional-cast bug is likeliest at the end of the list.
        e.value = 6
        assertEquals(10, awaitItem())

        cancelAndIgnoreRemainingEvents()
      }
    }

  /**
   * A source that re-emits an equal value produces **nothing**.
   *
   * This is the property the whole file exists for. `ProgressUpdater` rewrites `Audiobook.progress`
   * every second, so Room re-emits the row at tick rate with everything else identical; without the
   * dedup each tick would recompute and re-render downstream.
   */
  @Test
  fun `an unchanged result does not emit twice`() =
    runTest {
      val a = MutableStateFlow(1)
      val b = MutableStateFlow(1)
      val c = MutableStateFlow(1)
      val d = MutableStateFlow(1)
      val e = MutableStateFlow(1)

      // The combiner ignores `e`, so moving it changes a source without changing the result.
      combineDistinct(a, b, c, d, e) { v1, v2, v3, v4, _ -> v1 + v2 + v3 + v4 }.test {
        assertEquals(4, awaitItem())

        e.value = 99
        expectNoEvents()

        // And a real change still gets through, so the dedup is not simply swallowing everything —
        // `expectNoEvents` alone would pass against a combinator that had stopped emitting.
        a.value = 2
        assertEquals(5, awaitItem())

        cancelAndIgnoreRemainingEvents()
      }
    }

  /** The four-source overload, which the player's transport and utility groups both use. */
  @Test
  fun `four sources reach the combiner in declaration order`() =
    runTest {
      val a = MutableStateFlow("a")
      val b = MutableStateFlow(2)
      val c = MutableStateFlow(3.0)
      val d = MutableStateFlow(true)

      combineDistinct(a, b, c, d) { v1, v2, v3, v4 -> "$v1|$v2|$v3|$v4" }.test {
        assertEquals("a|2|3.0|true", awaitItem())
        cancelAndIgnoreRemainingEvents()
      }
    }
}
