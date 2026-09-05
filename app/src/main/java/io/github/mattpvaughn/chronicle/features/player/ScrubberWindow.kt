package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.EMPTY_CHAPTER

/**
 * The scrubber window a remote surface should draw: a position and a duration, in the same frame.
 */
data class ScrubberWindow(
  val positionMillis: Long,
  val durationMillis: Long,
)

/**
 * Narrows a book-framed position to the current chapter, for the session's scrubber (cu-165).
 *
 * Android Auto and the notification draw their seek bar from `PlaybackState.position` against
 * `METADATA_KEY_DURATION`. Both used to describe the whole track, while the *title* beside them
 * named the chapter — so on a single-file 47-hour audiobook a small drag skipped hours and the
 * readout did not match the title. It is the most-reported Auto complaint against both major
 * competitors (advplyr/audiobookshelf-app#1406, PaulWoitaschek/Voice#3432).
 *
 * Returns null when there is no usable chapter, and callers then publish the track window
 * unchanged: a book with no chapter data must keep a working bar, not get a dead one.
 *
 * **The two values must move together.** Publishing a chapter-length duration against a
 * track-framed position is worse than doing nothing — the bar would be the right size and point at
 * the wrong place — so this returns them as one value or not at all.
 */
fun chapterScrubberWindow(
  bookPosition: BookOffset,
  chapter: Chapter,
): ScrubberWindow? {
  if (chapter == EMPTY_CHAPTER) return null

  val start = chapter.bookStartTimeOffset.millis
  val end = chapter.bookEndTimeOffset.millis
  val duration = end - start
  if (duration <= 0L) return null

  // Clamped because the position is sampled a tick behind the chapter that was resolved from it,
  // so at a boundary it can land just outside. A negative position makes Auto draw a full bar.
  val position = (bookPosition.millis - start).coerceIn(0L, duration)
  return ScrubberWindow(positionMillis = position, durationMillis = duration)
}
