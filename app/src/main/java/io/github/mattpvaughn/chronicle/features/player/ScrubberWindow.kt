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
 * Auto and the notification draw their seek bar from `PlaybackState.position` against
 * `METADATA_KEY_DURATION`. Both described the whole track while the title beside them named the
 * chapter, so on a single-file 47-hour book a small drag skipped hours
 * (advplyr/audiobookshelf-app#1406, PaulWoitaschek/Voice#3432).
 *
 * Position and duration are returned **together or not at all**: a chapter-length duration against
 * a track-framed position draws a bar of the right size pointing at the wrong place. Null means the
 * caller keeps the track window, so a book without chapters gets a working bar rather than a dead
 * one.
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
