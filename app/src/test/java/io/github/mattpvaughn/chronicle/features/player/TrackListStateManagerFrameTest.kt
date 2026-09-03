package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.data.model.TrackIndex
import io.github.mattpvaughn.chronicle.testing.MultiTrackBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The two frames `TrackListStateManager` exposes, and the write that confused them.
 *
 * `currentBookPosition` and `currentTrackProgress` differ by the sum of the preceding tracks'
 * durations. `CurrentlyPlayingViewModel.seekRelative`'s service-is-dead branch wrote the *book*
 * position into `MediaItemTrack.progress` — an in-track column — so seeking with no service
 * running inflated the row by that sum, and `getActiveTrack` (furthest-started) then read a
 * corrupt position. Single-track books were unaffected, which is why it survived (cu-136).
 *
 * Also pins the sorted-index rule: `getActiveTrack()` sorts internally, so the index it produces
 * must be looked up in the sorted list. It was looked up in the unsorted one and agreed only
 * because both callers happened to pass a DAO-ordered list.
 */
class TrackListStateManagerFrameTest {
  private fun managerAtMidBook() =
    TrackListStateManager().apply {
      trackList = MultiTrackBook.midBookTracks()
      seekToActiveTrack()
    }

  @Test
  fun `the two frames differ by the preceding tracks' durations`() {
    val manager = managerAtMidBook()

    assertEquals(MultiTrackBook.MID_TRACK_POSITION, manager.currentTrackProgress)
    assertEquals(MultiTrackBook.MID_BOOK_OFFSET, manager.currentBookPosition)
    assertEquals(
      "the gap is exactly track 1's duration, which is what made the mix-up invisible on a" +
        " single-track book",
      MultiTrackBook.TRACK_DURATION,
      manager.currentBookPosition.millis - manager.currentTrackProgress.millis,
    )
    assertNotEquals(
      "if these were equal this test could not see the bug it exists for",
      manager.currentBookPosition.millis,
      manager.currentTrackProgress.millis,
    )
  }

  @Test
  fun `seekToActiveTrack lands on the furthest started track`() {
    val manager = managerAtMidBook()

    assertEquals(TrackIndex(1), manager.currentTrackIndex)
  }

  /**
   * The index addresses the *sorted* list, so an unsorted input must not move it. The old
   * `trackList.indexOf(getActiveTrack())` read the unsorted list while `getActiveTrack` sorted.
   */
  @Test
  fun `the resolved index does not depend on the order the tracks arrive in`() {
    val ordered = MultiTrackBook.midBookTracks()
    val shuffled = listOf(ordered[2], ordered[0], ordered[1])

    val fromOrdered =
      TrackListStateManager().apply {
        trackList = ordered
        seekToActiveTrack()
      }
    val fromShuffled =
      TrackListStateManager().apply {
        trackList = shuffled
        seekToActiveTrack()
      }

    assertEquals(fromOrdered.currentTrackIndex, fromShuffled.currentTrackIndex)
    assertEquals(fromOrdered.currentTrackProgress, fromShuffled.currentTrackProgress)
    assertEquals(fromOrdered.currentBookPosition, fromShuffled.currentBookPosition)
  }

  /**
   * A relative seek keeps both frames consistent. 300_000 forward from 750_000 lands at
   * 1_050_000 in the book, which is 450_000 into track 2 — still track index 1.
   */
  @Test
  fun `a relative seek advances both frames consistently`() {
    val manager = managerAtMidBook()

    manager.seekByRelative(300_000L)

    assertEquals(TrackIndex(1), manager.currentTrackIndex)
    assertEquals(450_000L, manager.currentTrackProgress.millis)
    assertEquals(1_050_000L, manager.currentBookPosition.millis)
  }

  /** And across a track boundary, where a frame confusion is largest. */
  @Test
  fun `a relative seek across a track boundary keeps both frames consistent`() {
    val manager = managerAtMidBook()

    // 750_000 + 600_000 = 1_350_000 in the book: 150_000 into track 3.
    manager.seekByRelative(600_000L)

    assertEquals(TrackIndex(2), manager.currentTrackIndex)
    assertEquals(150_000L, manager.currentTrackProgress.millis)
    assertEquals(1_350_000L, manager.currentBookPosition.millis)
  }
}
