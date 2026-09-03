package io.github.mattpvaughn.chronicle.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Human-readable durations for the player (cu-19).
 *
 * The player used to print `DateUtils.formatElapsedTime`, which gives `h:mm:ss` — so a 47-hour
 * book read `47:12:33/52:04:11`. RESEARCH_FINDINGS §3.1 convergent-grammar rule 3 is explicit that
 * every well-liked audio app avoids that: *"two-level, human-formatted progress … never raw
 * `h:mm:ss/h:mm:ss`"*. `6h 12m` is the form Prologue and SABP use and the one the design brief
 * asks for.
 *
 * Pure functions over millis so the wording is testable without a device or a `Context`; the
 * translated sentence around them is assembled in the ViewModel from `strings.xml`.
 */
class DurationFormatTest {
  @Test
  fun `hours and minutes read as hours and minutes`() {
    assertEquals("6h 12m", formatCoarseDuration(6 * HOUR + 12 * MINUTE))
    assertEquals("1h 1m", formatCoarseDuration(HOUR + MINUTE))
    assertEquals("47h 12m", formatCoarseDuration(47 * HOUR + 12 * MINUTE + 33 * SECOND))
  }

  /**
   * A whole number of hours drops the minutes rather than printing `6h 0m`, which reads as
   * unfinished text.
   */
  @Test
  fun `a whole hour omits the minutes`() {
    assertEquals("6h", formatCoarseDuration(6 * HOUR))
    assertEquals("1h", formatCoarseDuration(HOUR + 30 * SECOND))
  }

  @Test
  fun `under an hour reads as minutes only`() {
    assertEquals("32m", formatCoarseDuration(32 * MINUTE))
    assertEquals("59m", formatCoarseDuration(59 * MINUTE + 59 * SECOND))
    assertEquals("1m", formatCoarseDuration(MINUTE))
  }

  /**
   * Under a minute is "less than a minute", not "0m".
   *
   * A book with 40 seconds left is nearly finished, and `0m` reads as a rounding bug rather than
   * as information.
   */
  @Test
  fun `under a minute says so rather than rounding to zero`() {
    assertEquals("<1m", formatCoarseDuration(59 * SECOND))
    assertEquals("<1m", formatCoarseDuration(1L))
  }

  /** Exactly zero is its own case: nothing left, not "less than a minute". */
  @Test
  fun `zero is zero`() {
    assertEquals("0m", formatCoarseDuration(0L))
  }

  /**
   * A negative duration cannot happen from a correct derivation, but a clock skew or a
   * partly-synced book can produce one — and `-1h -12m` on screen is worse than clamping.
   */
  @Test
  fun `a negative duration clamps to zero`() {
    assertEquals("0m", formatCoarseDuration(-1L))
    assertEquals("0m", formatCoarseDuration(-5 * HOUR))
  }

  /**
   * Chapter positions stay precise: within a chapter, seconds are what the listener is tracking,
   * so this one keeps `m:ss` and only grows an hours field when it has to.
   */
  @Test
  fun `a chapter position keeps its seconds`() {
    assertEquals("0:01", formatPrecisePosition(SECOND))
    assertEquals("32:10", formatPrecisePosition(32 * MINUTE + 10 * SECOND))
    assertEquals("1:02:03", formatPrecisePosition(HOUR + 2 * MINUTE + 3 * SECOND))
    assertEquals("0:00", formatPrecisePosition(0L))
  }

  @Test
  fun `a precise position clamps at zero too`() {
    assertEquals("0:00", formatPrecisePosition(-1L))
  }

  /** Seconds truncate rather than round, so a position never reads ahead of the audio. */
  @Test
  fun `seconds truncate rather than round`() {
    assertEquals("0:01", formatPrecisePosition(SECOND + 999L))
    assertEquals("32m", formatCoarseDuration(32 * MINUTE + 59 * SECOND))
  }

  private companion object {
    const val SECOND = 1_000L
    const val MINUTE = 60 * SECOND
    const val HOUR = 60 * MINUTE
  }
}
