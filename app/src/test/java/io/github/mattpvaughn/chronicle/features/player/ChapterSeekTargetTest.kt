package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.TrackIndex
import io.github.mattpvaughn.chronicle.data.model.TrackOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Converting a book-absolute chapter offset into `seekTo` coordinates (cu-96).
 *
 * The multi-track fixture below is the thing that was missing when this bug was found: the owner's
 * books are single-file, so absolute and in-track offsets coincide and every wrong calculation
 * still landed in the right place. Three tracks of different durations make the two frames disagree
 * by construction, which is the only way to see the defect without a real multi-track book.
 */
class ChapterSeekTargetTest {
  // 10 minutes, 20 minutes, 30 minutes. Deliberately unequal: equal durations would let an
  // off-by-one in the track index produce a plausible-looking offset.
  private val tracks =
    listOf(
      MediaItemTrack(id = "t1", parentKey = "1001", index = 1, duration = 600_000L),
      MediaItemTrack(id = "t2", parentKey = "1001", index = 2, duration = 1_200_000L),
      MediaItemTrack(id = "t3", parentKey = "1001", index = 3, duration = 1_800_000L),
    )

  private fun chapter(
    trackId: String,
    bookStart: Long,
    index: Long = 1L,
  ) = Chapter(
    id = "c$index",
    index = index,
    trackId = trackId,
    bookId = "1001",
    bookStartTimeOffset = BookOffset(bookStart),
    bookEndTimeOffset = BookOffset(bookStart + 60_000L),
  )

  /** On the first track the two frames coincide — the single-file case that always worked. */
  @Test
  fun `a chapter on the first track keeps its offset`() {
    val target = chapterSeekTarget(chapter("t1", bookStart = 120_000L), tracks)

    assertEquals(ChapterSeekTarget(trackIndex = TrackIndex(0), inTrackOffset = TrackOffset(120_000L)), target)
  }

  /**
   * The bug. A chapter five minutes into track two sits at 900 000ms in the book, but only
   * 300 000ms into its own track. The old code passed 900 000 to `seekTo` as an in-track offset —
   * past the end of a 20-minute track, which Media3 clamps to the boundary.
   */
  @Test
  fun `a chapter on the second track is offset by the first track's duration`() {
    val target = chapterSeekTarget(chapter("t2", bookStart = 900_000L), tracks)

    assertEquals(
      "900000ms into the book is 300000ms into track 2, not 900000",
      ChapterSeekTarget(trackIndex = TrackIndex(1), inTrackOffset = TrackOffset(300_000L)),
      target,
    )
  }

  /** Third track: both preceding durations must be subtracted, not just the first. */
  @Test
  fun `a chapter on the third track subtracts both preceding tracks`() {
    val target = chapterSeekTarget(chapter("t3", bookStart = 2_400_000L), tracks)

    assertEquals(
      ChapterSeekTarget(trackIndex = TrackIndex(2), inTrackOffset = TrackOffset(600_000L)),
      target,
    )
  }

  /** A chapter exactly on a track boundary belongs to the track it names, at offset zero. */
  @Test
  fun `a chapter at the start of its track is at offset zero`() {
    val target = chapterSeekTarget(chapter("t2", bookStart = 600_000L), tracks)

    assertEquals(ChapterSeekTarget(trackIndex = TrackIndex(1), inTrackOffset = TrackOffset(0L)), target)
  }

  /**
   * Track order comes from `MediaItemTrack.compareTo`, not list order. A list handed over in the
   * wrong order must still resolve correctly, since the seek index addresses the *player's* queue.
   */
  @Test
  fun `tracks are ordered before indexing`() {
    val shuffled = listOf(tracks[2], tracks[0], tracks[1])

    val target = chapterSeekTarget(chapter("t2", bookStart = 900_000L), shuffled)

    assertEquals(ChapterSeekTarget(trackIndex = TrackIndex(1), inTrackOffset = TrackOffset(300_000L)), target)
  }

  /**
   * An unknown track id must refuse rather than guess. `indexOf` on a missing element returns -1,
   * and the old code passed that straight to `seekTo` as a media item index.
   */
  @Test
  fun `an unknown track resolves to nothing`() {
    assertNull(chapterSeekTarget(chapter("t9", bookStart = 0L), tracks))
    assertNull(chapterSeekTarget(chapter("t1", bookStart = 0L), emptyList()))
  }

  /**
   * A chapter whose offset precedes its own track means the two disagree — corrupt or partially
   * synced data. Seeking to a negative position throws, so it clamps to the track start.
   */
  @Test
  fun `an offset before its own track clamps to zero`() {
    val target = chapterSeekTarget(chapter("t3", bookStart = 60_000L), tracks)

    assertEquals(ChapterSeekTarget(trackIndex = TrackIndex(2), inTrackOffset = TrackOffset(0L)), target)
  }

  /** The threshold arithmetic: both operands book-absolute, so the answer is real. */
  @Test
  fun `millis into chapter is measured in the book frame`() {
    val ch = chapter("t2", bookStart = 900_000L)

    assertEquals(0L, millisIntoChapter(ch, BookOffset(900_000L)))
    assertEquals(5_000L, millisIntoChapter(ch, BookOffset(905_000L)))
  }

  /**
   * The specific misreading that shipped: an *in-track* position (300 000ms into track 2) compared
   * against a book-absolute chapter start (900 000ms) gives -600 000 — which is below any
   * threshold, so "previous chapter" always skipped back instead of restarting the current one.
   * That is the behaviour the owner reported on the device.
   */
  @Test
  fun `mixing frames yields a nonsense negative, which is the reported bug`() {
    val ch = chapter("t2", bookStart = 900_000L)
    val inTrackPosition = 300_000L

    assertEquals(-600_000L, millisIntoChapter(ch, BookOffset(inTrackPosition)))
  }
}
