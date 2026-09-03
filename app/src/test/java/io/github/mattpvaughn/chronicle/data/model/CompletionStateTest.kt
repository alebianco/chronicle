package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Completion is an explicit fact, separate from position (decision-16).
 *
 * Verified in the fixtures: Plex keeps `viewOffset` and `viewCount` independent — track 2001 carries
 * offset 1500 with viewCount 0. A book is finished because it was *marked* finished, not because its
 * position happens to be near the end.
 *
 * Two bugs this pins:
 *
 * 1. **`isCompleted()` reported true at 0% progress.** Its first clause was
 *    `progress < 10.seconds`, which is the *unstarted* case, not the finished one. It had no callers,
 *    so it was latent — and it is exactly the helper someone reaches for when adding a finished
 *    state to the library list.
 * 2. **"Mark as read" reported a book at 50%.** `markTracksInBookAsWatched` sets every track to
 *    `progress = 0, lastViewedAt = now`, and under decision-16's furthest-started rule every track
 *    then counts as started, so `getActiveTrack` returned the *last* one and book progress became
 *    the sum of all preceding durations. This is the owner's *"sometimes it brings to 0%, sometimes
 *    at a different position"*.
 */
class CompletionStateTest {
  private fun book(
    progress: Long,
    duration: Long = 3_600_000L,
    viewCount: Long = 0L,
  ) = Audiobook(
    id = "1001",
    source = 1L,
    title = "Dune",
    progress = progress,
    duration = duration,
    viewCount = viewCount,
  )

  @Test
  fun `an unstarted book is not completed`() {
    assertFalse(
      "0% progress is the unstarted case, not the finished one",
      book(progress = 0L).isCompleted(),
    )
  }

  @Test
  fun `a book a few seconds in is not completed`() {
    assertFalse(book(progress = 5_000L).isCompleted())
  }

  @Test
  fun `a book part way through is not completed`() {
    assertFalse(book(progress = 1_800_000L).isCompleted())
  }

  /** Near the end still counts as finished: nobody listens to the closing credits. */
  @Test
  fun `a book within the end window is completed`() {
    assertTrue(book(progress = 3_540_000L).isCompleted())
  }

  @Test
  fun `a book at its full duration is completed`() {
    assertTrue(book(progress = 3_600_000L).isCompleted())
  }

  /**
   * The exact edge of the window, which is where `>=` and `>` differ.
   *
   * With duration 3_600_000 and a 2-minute window the threshold is exactly 3_480_000. Every other
   * test here sits a comfortable distance either side of it, so a boundary mutant survived: the
   * comparison could flip to `>` and nothing failed. These two pin it from both directions.
   */
  @Test
  fun `a book exactly at the window threshold is completed`() {
    val threshold = 3_600_000L - BOOK_FINISHED_END_WINDOW

    assertTrue(
      "progress exactly at duration minus the window is finished",
      book(progress = threshold).isCompleted(),
    )
  }

  @Test
  fun `a book one millisecond short of the window is not completed`() {
    val threshold = 3_600_000L - BOOK_FINISHED_END_WINDOW

    assertFalse(
      "one ms before the threshold is still unfinished",
      book(progress = threshold - 1L).isCompleted(),
    )
  }

  /**
   * Pins the window's *value*, not just its use. A mutant replacing the constant with 0 left every
   * other assertion here passing, because they all sit far from the edge.
   */
  @Test
  fun `the finished window is two minutes`() {
    assertEquals(120_000L, BOOK_FINISHED_END_WINDOW)
  }

  /** An explicit mark-as-read is authoritative regardless of where the position sits. */
  @Test
  fun `a book marked as played is completed even at zero progress`() {
    assertTrue(
      "completion is an explicit fact, not inferred from position",
      book(progress = 0L, viewCount = 1L).isCompleted(),
    )
  }

  @Test
  fun `a book with no duration is not reported as completed`() {
    // Guards a divide-by-nothing style bug: an unloaded book has duration 0, and
    // `progress > duration - window` would be trivially true for any progress.
    assertFalse(book(progress = 0L, duration = 0L).isCompleted())
  }

  /**
   * Marking a book as read must not invent a position. Every track gets `viewCount = 1` and a zero
   * offset, so the book reads as finished *and* at the start — not at 50% on the last track.
   */
  @Test
  fun `tracks marked as watched report the book at the start, not part way through`() {
    val now = 9_000L
    val marked =
      listOf(
        MediaItemTrack(id = "1", parentKey = "1001", index = 1, duration = 1_000L, progress = 0L, lastViewedAt = now, viewCount = 1L),
        MediaItemTrack(id = "2", parentKey = "1001", index = 2, duration = 2_000L, progress = 0L, lastViewedAt = now, viewCount = 1L),
        MediaItemTrack(id = "3", parentKey = "1001", index = 3, duration = 3_000L, progress = 0L, lastViewedAt = now, viewCount = 1L),
      )

    assertEquals(
      "a book marked as read must not report a position part way through it",
      0L,
      marked.getProgress().millis,
    )
  }
}
