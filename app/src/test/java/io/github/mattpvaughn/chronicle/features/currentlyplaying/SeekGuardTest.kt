package io.github.mattpvaughn.chronicle.features.currentlyplaying

import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.model.getDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The percentage readout's arithmetic, which now shares a source with the timeline (cu-94).
 *
 * The percentage used to read the track list straight from Room — written every second by the
 * progress loop — while the timeline read `currentlyPlaying.track`, refreshed only by playback
 * callbacks. The DB write landed first, so the percentage visibly moved before the timeline did.
 * Two readouts of one fact, disagreeing; the same split cu-87 fixed for the chapter list.
 *
 * The derivation is reproduced here rather than exercised through the ViewModel: the ViewModel
 * needs a LiveData harness, and what broke was the *arithmetic of combining* a playing track with
 * the list around it.
 */
class SeekGuardTest {
  private fun track(
    id: String,
    index: Int,
    duration: Long,
    progress: Long = 0L,
  ) = MediaItemTrack(
    id = id,
    parentKey = "1001",
    title = "Track $index",
    index = index,
    duration = duration,
    progress = progress,
  )

  /** Mirrors `progressPercentageString`: tracks before the playing one, plus progress into it. */
  private fun percentOf(
    tracks: List<MediaItemTrack>,
    playing: MediaItemTrack,
  ): Int {
    val total = tracks.getDuration()
    if (tracks.isEmpty() || total == 0L) return 0
    val before = tracks.sorted().takeWhile { it.id != playing.id }.sumOf { it.duration }
    return ((((before + playing.progress) / total.toDouble()) * 100).roundToInt()).coerceIn(0, 100)
  }

  private val threeTracks =
    listOf(
      track("1", 1, duration = 1_000L),
      track("2", 2, duration = 1_000L),
      track("3", 3, duration = 2_000L),
    )

  @Test
  fun `progress into the first track counts only that track`() {
    val playing = track("1", 1, duration = 1_000L, progress = 500L)

    assertEquals(13, percentOf(threeTracks, playing))
  }

  /** The tracks *before* the playing one must be counted in full. */
  @Test
  fun `progress into a later track includes the tracks before it`() {
    val playing = track("3", 3, duration = 2_000L, progress = 1_000L)

    assertEquals("1000 + 1000 done, 1000 into the last = 3000 of 4000", 75, percentOf(threeTracks, playing))
  }

  @Test
  fun `an unstarted book reads zero`() {
    assertEquals(0, percentOf(threeTracks, track("1", 1, duration = 1_000L)))
  }

  @Test
  fun `the last track at its end reads one hundred`() {
    val playing = track("3", 3, duration = 2_000L, progress = 2_000L)

    assertEquals(100, percentOf(threeTracks, playing))
  }

  /** A book whose durations have not loaded must not divide by zero. */
  @Test
  fun `a book with no duration reads zero rather than throwing`() {
    val zeroDuration = listOf(track("1", 1, duration = 0L))

    assertEquals(0, percentOf(zeroDuration, zeroDuration.first()))
  }

  /** Playback order, not list order — the list arrives unsorted from the DB. */
  @Test
  fun `tracks are counted in playback order`() {
    val shuffled = listOf(threeTracks[2], threeTracks[0], threeTracks[1])
    val playing = track("3", 3, duration = 2_000L, progress = 1_000L)

    assertEquals(75, percentOf(shuffled, playing))
  }
}
