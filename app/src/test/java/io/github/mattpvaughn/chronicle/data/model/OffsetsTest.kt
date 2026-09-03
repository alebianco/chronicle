package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offset types carry a frame; these pin the arithmetic they permit.
 *
 * The interesting assertions are the *absences* — see `OffsetFrameCompileTest` for the mix-ups
 * that must not compile, which is the real guarantee and cannot be expressed here.
 */
class OffsetsTest {
  @Test
  fun `subtracting two offsets in the same frame gives a plain duration`() {
    assertEquals(60_000L, BookOffset(180_000L) - BookOffset(120_000L))
    assertEquals(5_000L, TrackOffset(15_000L) - TrackOffset(10_000L))
  }

  @Test
  fun `adding a duration keeps the frame`() {
    assertEquals(BookOffset(130_000L), BookOffset(120_000L) + 10_000L)
    assertEquals(TrackOffset(20_000L), TrackOffset(15_000L) + 5_000L)
  }

  @Test
  fun `offsets order within their frame`() {
    assertTrue(BookOffset(100L) < BookOffset(200L))
    assertTrue(TrackOffset(200L) > TrackOffset(100L))
    assertEquals(BookOffset(100L), BookOffset(100L))
    assertEquals(
      listOf(BookOffset(1L), BookOffset(5L), BookOffset(9L)),
      listOf(BookOffset(9L), BookOffset(1L), BookOffset(5L)).sorted(),
    )
  }

  /**
   * A negative seek position throws in Media3, and every conversion that could produce one
   * clamps. Keeping the clamp on the type means a caller cannot forget it and cannot write the
   * clamp slightly differently, which is how cu-115's sites drifted apart.
   */
  @Test
  fun `a track offset clamps at zero`() {
    assertEquals(TrackOffset.ZERO, TrackOffset(-1L).coerceAtLeastZero())
    assertEquals(TrackOffset.ZERO, TrackOffset(-500_000L).coerceAtLeastZero())
    assertEquals(TrackOffset.ZERO, TrackOffset(0L).coerceAtLeastZero())
    assertEquals(TrackOffset(1L), TrackOffset(1L).coerceAtLeastZero())
  }

  @Test
  fun `zero is the identity for both frames`() {
    assertEquals(BookOffset(0L), BookOffset.ZERO)
    assertEquals(TrackOffset(0L), TrackOffset.ZERO)
    assertEquals(0L, BookOffset(42L) - BookOffset(42L))
  }

  @Test
  fun `an unresolved track index is distinguishable from a real one`() {
    assertFalse(TrackIndex.NONE.isResolved)
    assertTrue(TrackIndex(0).isResolved)
    assertTrue(TrackIndex(7).isResolved)
    assertEquals(-1, TrackIndex.NONE.value)
  }

  /**
   * Room stores the plain millis, which is why no migration was needed. If a converter ever
   * started storing something else, every stored position would silently shift.
   */
  @Test
  fun `the Room converters round-trip the raw millis`() {
    val converters = OffsetConverters()

    assertEquals(120_000L, converters.fromBookOffset(BookOffset(120_000L)))
    assertEquals(BookOffset(120_000L), converters.toBookOffset(120_000L))
    assertEquals(15_000L, converters.fromTrackOffset(TrackOffset(15_000L)))
    assertEquals(TrackOffset(15_000L), converters.toTrackOffset(15_000L))

    // Negative and zero are both real stored values (a cleared progress is 0).
    assertEquals(BookOffset.ZERO, converters.toBookOffset(0L))
    assertEquals(TrackOffset(-1L), converters.toTrackOffset(-1L))
  }
}
