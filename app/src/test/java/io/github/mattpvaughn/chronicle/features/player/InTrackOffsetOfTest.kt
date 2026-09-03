package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.TrackOffset
import io.github.mattpvaughn.chronicle.testing.MultiTrackBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The single book → track conversion, and the bug that came from having three copies of it.
 *
 * `CurrentlyPlayingViewModel.jumpToChapter`, `CurrentlyPlayingViewModel.seekTo` and
 * `AudiobookDetailsViewModel.jumpToChapter` each inlined this arithmetic, all three written as
 * `tracks.takeWhile { it.id != trackId }.sumOf { it.duration }`. `takeWhile` stops at the first
 * element that fails the predicate — so when the id *is* present it is right, and when the id is
 * **absent it sums every track**, silently returning a huge offset instead of admitting it could
 * not resolve one. Two of this family's six bugs were that shape (cu-136).
 */
class InTrackOffsetOfTest {
  private val tracks = MultiTrackBook.tracks()

  /** Track 1 starts at zero, so the two frames coincide — the single-file case. */
  @Test
  fun `an offset in the first track is unchanged`() {
    assertEquals(
      TrackOffset(120_000L),
      inTrackOffsetOf(BookOffset(120_000L), "2001", tracks),
    )
  }

  /**
   * The case the whole task exists for: 750_000 in the book is 150_000 into track 2. Passing the
   * book value through unconverted overshoots by a full track duration.
   */
  @Test
  fun `an offset in a later track is measured from that track's start`() {
    assertEquals(
      MultiTrackBook.MID_TRACK_POSITION,
      inTrackOffsetOf(MultiTrackBook.MID_BOOK_OFFSET, MultiTrackBook.MID_TRACK_ID, tracks),
    )
  }

  @Test
  fun `an offset exactly on a track boundary is zero into that track`() {
    assertEquals(TrackOffset.ZERO, inTrackOffsetOf(BookOffset(600_000L), "2002", tracks))
    assertEquals(TrackOffset.ZERO, inTrackOffsetOf(BookOffset(1_200_000L), "2003", tracks))
  }

  /**
   * **The `takeWhile` bug.** An unknown id must report that it could not resolve, so the caller
   * can decline to seek. The three inlined copies summed every track instead and returned
   * `750_000 - 1_800_000` clamped to zero — a silent seek to the start of whichever track the
   * player happened to be on.
   */
  @Test
  fun `an unknown track id resolves to null rather than summing every track`() {
    assertNull(inTrackOffsetOf(MultiTrackBook.MID_BOOK_OFFSET, "no-such-track", tracks))
  }

  @Test
  fun `an empty track list resolves to null`() {
    assertNull(inTrackOffsetOf(BookOffset(120_000L), "2001", emptyList()))
  }

  /** Sorted internally, so a DAO or network list in any order gives the same answer. */
  @Test
  fun `the answer does not depend on the order the tracks arrive in`() {
    val shuffled = listOf(tracks[2], tracks[0], tracks[1])

    assertEquals(
      inTrackOffsetOf(MultiTrackBook.MID_BOOK_OFFSET, MultiTrackBook.MID_TRACK_ID, tracks),
      inTrackOffsetOf(MultiTrackBook.MID_BOOK_OFFSET, MultiTrackBook.MID_TRACK_ID, shuffled),
    )
  }

  /**
   * An offset preceding its own track means the two disagree — corrupt or partly synced data.
   * Seeking to a negative position throws, so it clamps rather than propagating.
   */
  @Test
  fun `an offset before its own track clamps to zero`() {
    assertEquals(TrackOffset.ZERO, inTrackOffsetOf(BookOffset(60_000L), "2003", tracks))
  }

  /**
   * `chapterSeekTarget` delegates here, so the two cannot drift apart — which is the point of
   * there being one conversion. Pinned because the previous arrangement had a correct function
   * sitting next to two inlined copies that were bypassing it.
   */
  @Test
  fun `chapterSeekTarget agrees with the conversion it delegates to`() {
    MultiTrackBook.chapters().forEach { chapter ->
      val viaTarget = chapterSeekTarget(chapter, tracks)?.inTrackOffset
      val direct = inTrackOffsetOf(chapter.bookStartTimeOffset, chapter.trackId, tracks)

      assertEquals("chapter ${chapter.title}", direct, viaTarget)
    }
  }
}
