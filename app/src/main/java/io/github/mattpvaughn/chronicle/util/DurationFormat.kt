package io.github.mattpvaughn.chronicle.util

import java.util.concurrent.TimeUnit

/**
 * A duration as `6h 12m`, `32m`, or `<1m`.
 *
 * The player used to print `DateUtils.formatElapsedTime` everywhere, so a 47-hour book — the
 * owner's real library has several — read `47:12:33/52:04:11`. RESEARCH_FINDINGS §3.1's
 * convergent-grammar rule 3 is that every well-liked audio app avoids exactly that: *"two-level,
 * human-formatted progress … never raw `h:mm:ss/h:mm:ss`"* (cu-19).
 *
 * Two formats here, because the two levels want different precision: this one for a **span** —
 * how much book is left, where nobody tracks 47 hours to the second — and [formatPrecisePosition]
 * for a **position inside a chapter**, where seconds are what the listener is following. Both are
 * pure functions over millis with no `Context`, so the wording is unit-testable; the translated
 * sentence around them is assembled from `strings.xml` (rule 5).
 *
 * Truncates rather than rounds, so a readout never claims more elapsed time than the audio has
 * played. A whole number of hours omits the minutes (`6h`, not `6h 0m`, which reads as unfinished
 * text), and anything under a minute is `<1m` rather than `0m` — a book with forty seconds left is
 * nearly done, and `0m` reads as a rounding bug. Exactly zero is `0m`, which is a real state.
 *
 * A negative input clamps: it cannot arise from a correct derivation, but a clock skew or a
 * partly-synced book can produce one, and `-1h -12m` on screen is worse than clamping.
 */
fun formatCoarseDuration(millis: Long): String {
  if (millis <= 0L) return "0m"

  val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis)
  if (totalMinutes == 0L) return "<1m"

  val hours = totalMinutes / MINUTES_PER_HOUR
  val minutes = totalMinutes % MINUTES_PER_HOUR
  return when {
    hours == 0L -> "${minutes}m"
    minutes == 0L -> "${hours}h"
    else -> "${hours}h ${minutes}m"
  }
}

/**
 * A position as `32:10`, or `1:02:03` once it passes an hour.
 *
 * Deliberately *not* `DateUtils.formatElapsedTime`, which pads to `0:32:10` at the hour boundary
 * and is what produced the raw strings this replaces. Seconds truncate, for the same reason as
 * [formatCoarseDuration]. Clamps at zero — a negative seek position is impossible to display
 * meaningfully.
 */
fun formatPrecisePosition(millis: Long): String {
  val clamped = millis.coerceAtLeast(0L)
  val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(clamped)
  val hours = totalSeconds / SECONDS_PER_HOUR
  val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
  val seconds = totalSeconds % SECONDS_PER_MINUTE

  return if (hours > 0L) {
    "%d:%02d:%02d".format(hours, minutes, seconds)
  } else {
    "%d:%02d".format(minutes, seconds)
  }
}

private const val MINUTES_PER_HOUR = 60L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
