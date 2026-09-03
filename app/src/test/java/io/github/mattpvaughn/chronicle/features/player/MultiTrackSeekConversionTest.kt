package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.data.model.TrackIndex
import io.github.mattpvaughn.chronicle.data.model.TrackOffset
import io.github.mattpvaughn.chronicle.testing.MultiTrackBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Book-absolute → (track, in-track offset), on a genuinely multi-track book (cu-115).
 *
 * `ChapterSeekTarget` has existed since cu-96 and does this conversion correctly, but it was only
 * ever exercised against single-track fixtures — where the conversion is the identity, so a broken
 * one passes. These cases use the three-track fixture, where a missed conversion is out by a whole
 * track duration.
 *
 * The same arithmetic is inlined at two call sites in `CurrentlyPlayingViewModel` (the
 * jump-to-chapter path from cu-96, and the chapter slider fixed under cu-115). The duplication is
 * a known smell; these tests pin the *rule* so all three agree, and a later consolidation onto
 * `chapterSeekTarget` has something to check itself against.
 */
class MultiTrackSeekConversionTest {
  private val tracks = MultiTrackBook.tracks()
  private val chapters = MultiTrackBook.chapters()

  /** Chapter 1 starts at the very beginning: track 0, offset 0. */
  @Test
  fun `the first chapter converts to the first track at zero`() {
    val target = chapterSeekTarget(chapters[0], tracks)

    assertEquals(TrackIndex(0), target?.trackIndex)
    assertEquals(TrackOffset(0L), target?.inTrackOffset)
  }

  /**
   * Chapter 2 starts at 300_000 — **inside** track 1, not on a boundary. This is the case a
   * per-track offset of `0` (the cu-13/cu-49 bug) gets wrong.
   */
  @Test
  fun `a chapter starting mid-track keeps its in-track offset`() {
    val target = chapterSeekTarget(chapters[1], tracks)

    assertEquals(TrackIndex(0), target?.trackIndex)
    assertEquals(300_000L, target?.inTrackOffset?.millis)
  }

  /**
   * Chapter 3 starts at 600_000, which is exactly where track 2 begins. The book offset and the
   * in-track offset differ by a full track here, so passing the book value unconverted would seek
   * 10 minutes too far — the failure `ChapterSeekTarget` exists to prevent.
   */
  @Test
  fun `a chapter starting on a track boundary converts to offset zero of that track`() {
    val target = chapterSeekTarget(chapters[2], tracks)

    assertEquals(TrackIndex(1), target?.trackIndex)
    assertEquals(
      "the book offset is 600000; unconverted it would overshoot into track 2",
      0L,
      target?.inTrackOffset?.millis,
    )
  }

  /** Chapter 4 starts at 900_000: track 2 (index 1), 300_000 into it. */
  @Test
  fun `a chapter in the middle of a later track converts to a track-relative offset`() {
    val target = chapterSeekTarget(chapters[3], tracks)

    assertEquals(TrackIndex(1), target?.trackIndex)
    assertEquals(300_000L, target?.inTrackOffset?.millis)
  }

  /** The last chapter, to cover the final track. */
  @Test
  fun `the last chapter converts into the last track`() {
    val target = chapterSeekTarget(chapters[5], tracks)

    assertEquals(TrackIndex(2), target?.trackIndex)
    assertEquals(300_000L, target?.inTrackOffset?.millis)
  }

  /**
   * Every chapter must round-trip: converting to (track, offset) and back to a book offset returns
   * the chapter's own start. This is the invariant the individual cases above only sample, and it
   * is what a future refactor should be held to.
   */
  @Test
  fun `every chapter round-trips back to its book offset`() {
    val ordered = tracks.sorted()

    chapters.forEach { chapter ->
      val target = requireNotNull(chapterSeekTarget(chapter, tracks)) { "no target for $chapter" }
      val trackStart = ordered.take(target.trackIndex.value).sumOf { it.duration }

      assertEquals(
        "chapter ${chapter.title} must round-trip",
        chapter.bookStartTimeOffset.millis,
        trackStart + target.inTrackOffset.millis,
      )
    }
  }

  /** Order of the incoming list must not matter — it arrives unordered from the database. */
  @Test
  fun `the conversion does not depend on the order of the track list`() {
    val shuffled = listOf(tracks[2], tracks[0], tracks[1])

    assertEquals(
      chapterSeekTarget(chapters[3], tracks),
      chapterSeekTarget(chapters[3], shuffled),
    )
  }

  /** A chapter naming a track that is not present must refuse rather than guess an index. */
  @Test
  fun `a chapter whose track is missing yields no target`() {
    val orphan = chapters[3].copy(trackId = "9999")

    assertNull(chapterSeekTarget(orphan, tracks))
  }
}
