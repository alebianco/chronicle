package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack

/**
 * Where to seek for a chapter, in the coordinates `Player.seekTo` actually takes (cu-96).
 *
 * `seekTo(mediaItemIndex, positionMs)` wants a **track index** and a position **within that track**.
 * `Chapter.bookStartTimeOffset` is absolute within the *book*. The two coincide on a single-file
 * audiobook — which is every book in the owner's library — so `skipToNext`/`skipToPrevious` passed
 * the absolute offset straight to `seekTo` and worked by accident. On a genuinely multi-track book
 * the offset overshoots the target track, and because Media3 clamps rather than throwing, the
 * symptom is landing on a track boundary instead of an error.
 *
 * The conversion is the whole fix, so it lives here rather than inline: it is pure arithmetic over
 * a track list, testable without a player, a device, or a real multi-track book.
 */
data class ChapterSeekTarget(
  val trackIndex: Int,
  val inTrackOffsetMillis: Long,
)

/**
 * Converts [chapter]'s book-absolute start into a track index and an in-track offset.
 *
 * Resolution goes through [Chapter.trackId] rather than by summing durations until the offset
 * fits: the track id is the fact the server gave us, whereas a duration sum silently drifts when a
 * track's duration is missing or wrong (which is exactly the condition a broken book presents).
 * The subtraction then only has to answer "how far into *that* track", using the track's own start.
 *
 * @return null when [chapter] names a track that is not in [tracks]. The caller must not seek —
 *   guessing an index would seek somewhere arbitrary, which is the failure this replaces.
 */
fun chapterSeekTarget(
  chapter: Chapter,
  tracks: List<MediaItemTrack>,
): ChapterSeekTarget? {
  val ordered = tracks.sorted()
  val trackIndex = ordered.indexOfFirst { it.id == chapter.trackId }
  if (trackIndex == -1) return null

  val trackStart = ordered.take(trackIndex).sumOf { it.duration }
  // Clamped at zero: a chapter whose recorded offset precedes its own track means the two disagree,
  // and seeking to a negative position would throw. Starting the track over is the safe reading.
  val inTrack = (chapter.bookStartTimeOffset - trackStart).coerceAtLeast(0L)
  return ChapterSeekTarget(trackIndex = trackIndex, inTrackOffsetMillis = inTrack)
}

/**
 * How far into [chapter] the listener is, given a book-absolute [bookPosition].
 *
 * Used to decide whether "previous chapter" means *restart this one* or *go back one*. Both
 * operands must be in the same frame; mixing them is what made the threshold compare an in-track
 * position against an absolute offset and answer nonsense on a multi-track book.
 */
fun millisIntoChapter(
  chapter: Chapter,
  bookPosition: Long,
): Long = bookPosition - chapter.bookStartTimeOffset
