package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The empty-and-absent cases on the `List<MediaItemTrack>` helpers.
 *
 * These are the branches nothing reached: every existing suite hands them a populated, well-formed
 * list, which is exactly the input that cannot show what happens when a book has no tracks yet —
 * the state a library sits in between a refresh writing the book row and the track fetch landing.
 *
 * Each guard here has a different intended failure mode, and that is the point of pinning them:
 * two return a neutral value, one returns a sentinel track, and one deliberately **throws**.
 * A future refactor that made them uniform would be wrong in three of the four cases.
 */
class TrackListEdgeCaseTest {
  private fun track(
    id: String,
    index: Int,
    duration: Long,
    progress: Long = 0L,
  ) = MediaItemTrack(
    id = id,
    source = TEST_SOURCE,
    index = index,
    duration = duration,
    progress = progress,
    parentKey = "1001",
  )

  private val tracks =
    listOf(
      track("1", index = 1, duration = 1_000L),
      track("2", index = 2, duration = 2_000L),
      track("3", index = 3, duration = 3_000L),
    )

  @Test
  fun `an offset inside the first track resolves to it`() {
    assertEquals("1", tracks.getTrackContainingOffset(500L).id)
  }

  @Test
  fun `an offset inside a later track resolves to it`() {
    // 1_000 consumed by track 1, so 1_500 lands 500ms into track 2.
    assertEquals("2", tracks.getTrackContainingOffset(1_500L).id)
    assertEquals("3", tracks.getTrackContainingOffset(4_000L).id)
  }

  /** The fold subtracts each duration and returns on `<= 0`, so a boundary belongs to the earlier
   *  track. Pinned so a change from `<=` to `<` — which would push every boundary seek one track
   *  forward — cannot pass unnoticed. */
  @Test
  fun `an offset exactly on a boundary belongs to the earlier track`() {
    assertEquals("1", tracks.getTrackContainingOffset(1_000L).id)
    assertEquals("2", tracks.getTrackContainingOffset(3_000L).id)
  }

  /**
   * Past the end the fold runs out without returning, and the function falls through to
   * `EMPTY_TRACK`. A seek beyond the book must land on the sentinel rather than the last track,
   * because "past the end" and "in the final track" are different facts to the caller.
   */
  @Test
  fun `an offset past the end returns the empty track`() {
    assertEquals(EMPTY_TRACK, tracks.getTrackContainingOffset(99_999L))
  }

  @Test
  fun `an empty or null track list returns the empty track rather than throwing`() {
    assertEquals(EMPTY_TRACK, emptyList<MediaItemTrack>().getTrackContainingOffset(0L))
    assertEquals(EMPTY_TRACK, (null as List<MediaItemTrack>?).getTrackContainingOffset(500L))
  }

  @Test
  fun `progress percentage is zero for an empty list`() {
    assertEquals(0, emptyList<MediaItemTrack>().getProgressPercentage())
  }

  /**
   * A zero total duration is the divide-by-zero guard. It is reachable in practice: a book whose
   * tracks are known but whose durations have not been fetched reports 0, and without the guard
   * the percentage would be `NaN` rendered into the library list.
   */
  @Test
  fun `progress percentage is zero when the tracks report no duration`() {
    val durationless = listOf(track("1", index = 1, duration = 0L, progress = 0L))

    assertEquals(0, durationless.getProgressPercentage())
  }

  @Test
  fun `progress percentage rounds to the nearest whole percent`() {
    // 3_000 of 6_000 = 50%.
    val half =
      listOf(
        track("1", index = 1, duration = 1_000L, progress = 1_000L),
        track("2", index = 2, duration = 2_000L, progress = 2_000L),
        track("3", index = 3, duration = 3_000L, progress = 0L),
      )

    assertEquals(50, half.getProgressPercentage())
  }

  @Test
  fun `start time is zero for an empty list`() {
    assertEquals(0L, emptyList<MediaItemTrack>().getTrackStartTime(tracks.first()))
  }

  /**
   * A track that is not in the list returns 0 rather than summing the whole list. That distinction
   * is load-bearing: `inTrackOffsetOf` was written because three call sites used
   * `takeWhile { it.id != trackId }.sumOf { … }`, which sums **every** track when the id is absent
   * and silently reports a position at the end of the book.
   */
  @Test
  fun `start time is zero for a track absent from the list`() {
    val stranger = track("999", index = 9, duration = 500L)

    assertEquals(0L, tracks.getTrackStartTime(stranger))
  }

  @Test
  fun `start time sums the tracks before it in playback order`() {
    assertEquals(0L, tracks.getTrackStartTime(tracks[0]))
    assertEquals(1_000L, tracks.getTrackStartTime(tracks[1]))
    assertEquals(3_000L, tracks.getTrackStartTime(tracks[2]))
  }

  /** Unsorted input must not change the answer — the function sorts internally. */
  @Test
  fun `start time ignores the order the list arrives in`() {
    val shuffled = listOf(tracks[2], tracks[0], tracks[1])

    assertEquals(3_000L, shuffled.getTrackStartTime(tracks[2]))
  }

  /**
   * `getActiveTrack` is the one that **throws** rather than returning a sentinel, and that is
   * deliberate: there is no meaningful "current track" of a book with no tracks, and returning
   * `EMPTY_TRACK` would let a caller start playback against an id that resolves to nothing.
   */
  @Test
  fun `active track throws on an empty list rather than returning a sentinel`() {
    assertThrows(IllegalStateException::class.java) {
      emptyList<MediaItemTrack>().getActiveTrack()
    }
  }

  @Test
  fun `padded index pads to the requested width`() {
    assertEquals("007", track("7", index = 7, duration = 1L).paddedIndex(3))
    assertEquals("07", track("7", index = 7, duration = 1L).paddedIndex(2))
    // Already wider than the pad: left alone rather than truncated.
    assertEquals("107", track("107", index = 107, duration = 1L).paddedIndex(2))
  }
}
