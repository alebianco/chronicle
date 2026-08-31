package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which track a book is "at", and therefore what its position is.
 *
 * Implements decision-16. The old rule was `maxByOrNull { it.lastViewedAt }` — the most recently
 * *touched* track — and book position was that track's offset plus the durations before it. Across
 * devices that makes position **non-monotonic**: device A listening in track 3 and device B in
 * track 7 means the reported position jumps between two unrelated points depending on which
 * `lastViewedAt` is larger. That is the owner's *"same account on different devices reports WILDLY
 * different positions"*.
 *
 * The rule now: the **furthest started** track. A book is listened to front to back, so position
 * advances rather than oscillating, and two devices converge instead of fighting.
 *
 * A track counts as started if it has a non-zero offset, has been viewed, or lies before a track
 * that has. The last clause matters: a listener who is 10 seconds into track 5 has *started* track
 * 5 even though tracks 1–4 may have zero offsets after being played through and reset.
 */
class ActiveTrackTest {
  private fun track(
    id: String,
    index: Int,
    duration: Long = 1_000L,
    progress: Long = 0L,
    lastViewedAt: Long = 0L,
  ) = MediaItemTrack(
    id = id,
    parentKey = "1001",
    index = index,
    duration = duration,
    progress = progress,
    lastViewedAt = lastViewedAt,
  )

  @Test
  fun `an untouched book is at its first track`() {
    val tracks = listOf(track("1", 1), track("2", 2), track("3", 3))

    assertEquals("1", tracks.getActiveTrack().id)
  }

  @Test
  fun `a book part way through one track is at that track`() {
    val tracks =
      listOf(
        track("1", 1, progress = 0L, lastViewedAt = 100L),
        track("2", 2, progress = 500L, lastViewedAt = 200L),
        track("3", 3),
      )

    assertEquals("2", tracks.getActiveTrack().id)
  }

  /**
   * The regression this rule exists for. Device B most recently opened an *earlier* track; the
   * book's position must not move backwards to it.
   */
  @Test
  fun `a recently touched earlier track does not pull the position backwards`() {
    val tracks =
      listOf(
        // Touched most recently, but earlier in the book.
        track("1", 1, progress = 200L, lastViewedAt = 9_000L),
        track("2", 2, progress = 0L, lastViewedAt = 500L),
        // Furthest started.
        track("3", 3, progress = 300L, lastViewedAt = 500L),
      )

    assertEquals(
      "position must advance, not follow whichever device touched a track last",
      "3",
      tracks.getActiveTrack().id,
    )
  }

  /** A started track after a run of played-through zero-offset tracks is still the position. */
  @Test
  fun `zero offsets before a started track do not reset the position`() {
    val tracks =
      listOf(
        track("1", 1, progress = 0L, lastViewedAt = 100L),
        track("2", 2, progress = 0L, lastViewedAt = 200L),
        track("3", 3, progress = 50L, lastViewedAt = 300L),
        track("4", 4),
      )

    assertEquals("3", tracks.getActiveTrack().id)
  }

  /** Order in the list must not matter; playback order (disc, index) decides. */
  @Test
  fun `the furthest started track is found regardless of list order`() {
    val tracks =
      listOf(
        track("3", 3, progress = 300L, lastViewedAt = 500L),
        track("1", 1, progress = 200L, lastViewedAt = 9_000L),
        track("2", 2),
      )

    assertEquals("3", tracks.getActiveTrack().id)
  }

  @Test
  fun `book progress is the active track offset plus everything before it`() {
    val tracks =
      listOf(
        track("1", 1, duration = 1_000L, progress = 0L, lastViewedAt = 100L),
        track("2", 2, duration = 2_000L, progress = 500L, lastViewedAt = 200L),
        track("3", 3, duration = 3_000L),
      )

    assertEquals(1_000L + 500L, tracks.getProgress())
  }

  /** Book progress must be monotonic in the same scenario as the active-track test above. */
  @Test
  fun `book progress does not jump backwards when an earlier track is touched`() {
    val tracks =
      listOf(
        track("1", 1, duration = 1_000L, progress = 200L, lastViewedAt = 9_000L),
        track("2", 2, duration = 2_000L, progress = 0L, lastViewedAt = 500L),
        track("3", 3, duration = 3_000L, progress = 300L, lastViewedAt = 500L),
      )

    assertEquals(
      "the old rule reported 200ms into a 6s book; the real position is in track 3",
      1_000L + 2_000L + 300L,
      tracks.getProgress(),
    )
  }

  @Test
  fun `an empty list has no progress`() {
    assertEquals(0L, emptyList<MediaItemTrack>().getProgress())
  }

  @Test
  fun `a single track book is at that track`() {
    val tracks = listOf(track("1", 1, duration = 5_000L, progress = 2_500L))

    assertEquals("1", tracks.getActiveTrack().id)
    assertEquals(2_500L, tracks.getProgress())
  }

  /** A fully played book sits at its last track, not back at the first. */
  @Test
  fun `a finished book is at its last track`() {
    val tracks =
      listOf(
        track("1", 1, duration = 1_000L, progress = 1_000L, lastViewedAt = 100L),
        track("2", 2, duration = 2_000L, progress = 2_000L, lastViewedAt = 200L),
      )

    assertEquals("2", tracks.getActiveTrack().id)
  }
}
