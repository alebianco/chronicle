package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A deliberate backwards seek across a track boundary must survive a refresh.
 *
 * Measured failure before the fix, on a live 107-track book: seeking back three chapters moved the
 * position from 2 296 261 to 1 564 209, and a forced refresh put it back to **1 910 473** — the
 * seek undone by 346 s (cu-131).
 *
 * The cause is that [getActiveTrack] takes the *furthest* started track regardless of recency, so
 * a stale `progress` left on a later track outranks the newer position the listener chose. The fix
 * clears the tail; these tests pin both that behaviour and the two rules it must not break.
 */
class BackwardsSeekTest {
  private fun track(
    index: Int,
    progress: Long = 0L,
    lastViewedAt: Long = 0L,
    discNumber: Int = 1,
  ) = MediaItemTrack(
    id = "t$index",
    parentKey = "b1",
    title = "Track $index",
    index = index,
    discNumber = discNumber,
    duration = 600_000L,
    progress = progress,
    lastViewedAt = lastViewedAt,
  )

  @Test
  fun `the furthest started track wins, which is what breaks a backwards seek`() {
    // The live shape: the seek landed in track 5, but track 6 still holds stale progress.
    val tracks =
      listOf(
        track(5, progress = 18_075L, lastViewedAt = 1788413872797L),
        track(6, progress = 3_092L, lastViewedAt = 1788413849136L),
      )

    assertEquals(
      "track 6 wins despite track 5 having the newer lastViewedAt — this is the bug",
      "t6",
      tracks.getActiveTrack().id,
    )
  }

  @Test
  fun `clearing the tail makes the seeked-to track active`() {
    // What the fix does: the later track's stale progress is zeroed, so the seek sticks.
    val tracks =
      listOf(
        track(5, progress = 18_075L, lastViewedAt = 1788413872797L),
        track(6, progress = 0L, lastViewedAt = 1788413849136L),
      )

    assertEquals("t5", tracks.getActiveTrack().id)
  }

  @Test
  fun `a book marked as read still reports no active progress`() {
    // The rule getActiveTrack's KDoc protects: markTracksInBookAsWatched sets progress = 0 and
    // lastViewedAt = now on *every* track. A recency-based fix would have made this report a
    // mid-book position; clearing the tail leaves it correct.
    val now = 1788413900000L
    val tracks = (1..3).map { track(it, progress = 0L, lastViewedAt = now) }

    assertEquals(
      "with no progress anywhere, the first track is active — the book reads as 0%",
      "t1",
      tracks.getActiveTrack().id,
    )
  }

  @Test
  fun `two-device convergence is unaffected when the tail is genuinely started`() {
    // cu-90, verified working on real devices: a device that listened further ahead must still
    // win. The fix only clears progress *after* the track being written, so a legitimately
    // further-on tail is untouched.
    val tracks =
      listOf(
        track(1, progress = 400_000L, lastViewedAt = 1788411046910L),
        track(2, progress = 24_927L, lastViewedAt = 1788412482105L),
      )

    assertEquals("t2", tracks.getActiveTrack().id)
  }

  @Test
  fun `ordering is by disc then index, not list order`() {
    // The tail is identified by (discNumber, index), so a list arriving out of order from Room or
    // the network must not change which track counts as later.
    val tracks =
      listOf(
        track(1, progress = 5_000L, discNumber = 2),
        track(9, progress = 5_000L, discNumber = 1),
      )

    assertEquals(
      "disc 2 track 1 is later than disc 1 track 9",
      "t1",
      tracks.getActiveTrack().id,
    )
  }
}
