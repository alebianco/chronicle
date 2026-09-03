package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.TrackIndex
import io.github.mattpvaughn.chronicle.data.model.TrackOffset

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
  val trackIndex: TrackIndex,
  val inTrackOffset: TrackOffset,
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
  // The index must come from the **sorted** list, because that is the order the player's playlist
  // is built in and `seekTo`'s `mediaItemIndex` addresses. [TrackIndex] says which list (cu-136).
  val ordered = tracks.sorted()
  val trackIndex = ordered.indexOfFirst { it.id == chapter.trackId }
  if (trackIndex == -1) return null

  // Clamped at zero inside [inTrackOffsetOf]: a chapter whose recorded offset precedes its own
  // track means the two disagree, and seeking to a negative position would throw. Starting the
  // track over is the safe reading.
  val inTrack =
    inTrackOffsetInSorted(chapter.bookStartTimeOffset, chapter.trackId, ordered) ?: return null
  return ChapterSeekTarget(trackIndex = TrackIndex(trackIndex), inTrackOffset = inTrack)
}

/**
 * [bookOffset] expressed as an offset within the track named by [trackId].
 *
 * The one home for book → track. Three separate sites inlined this arithmetic —
 * `CurrentlyPlayingViewModel.jumpToChapter`, `CurrentlyPlayingViewModel.seekTo` and
 * `AudiobookDetailsViewModel.jumpToChapter` — all three written as
 * `tracks.takeWhile { it.id != trackId }.sumOf { it.duration }`, which **sums every track when
 * the id is absent** rather than reporting that it could not resolve one. Two of the six bugs in
 * this family were exactly that shape, so the duplication is the defect (cu-136).
 *
 * @param tracks the book's tracks, in any order — sorted internally, because the order that
 *   matters is the player's playlist order and not the caller's.
 * @return null when [trackId] is not among [tracks]. A caller that cannot resolve a track must
 *   decide what to do; guessing an offset seeks somewhere arbitrary.
 */
fun inTrackOffsetOf(
  bookOffset: BookOffset,
  trackId: String,
  tracks: List<MediaItemTrack>,
): TrackOffset? = inTrackOffsetInSorted(bookOffset, trackId, tracks.sorted())

/**
 * [inTrackOffsetOf] for a list already in playback order, so a caller that has sorted does not
 * sort twice on a per-seek path.
 */
private fun inTrackOffsetInSorted(
  bookOffset: BookOffset,
  trackId: String,
  ordered: List<MediaItemTrack>,
): TrackOffset? {
  val trackIndex = ordered.indexOfFirst { it.id == trackId }
  if (trackIndex == -1) return null

  val trackStart = BookOffset(ordered.take(trackIndex).sumOf { it.duration })
  return TrackOffset(bookOffset - trackStart).coerceAtLeastZero()
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
  bookPosition: BookOffset,
): Long = bookPosition - chapter.bookStartTimeOffset
