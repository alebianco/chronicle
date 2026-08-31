package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_PAUSED
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_PLAYING
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_STOPPED
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a track or book is marked watched — the arithmetic, at its boundaries.
 *
 * **Written because mutation testing asked for it (cu-57).** `ProgressReporter` had 96% *line*
 * coverage and killed 3 of 45 mutants: the existing tests execute this method but assert almost
 * nothing about it. PIT reported `changed conditional boundary` and
 * `Replaced long subtraction with addition` surviving in `markFinishedIfNeeded`, meaning the two
 * comparisons that decide "is this finished?" could be silently wrong.
 *
 * The consequence of getting them wrong is not cosmetic: marking a book watched resets its
 * position, so an off-by-one here throws away the listener's place — the same family of bug as
 * cu-9's mid-playback `setWatched`.
 */
class MarkFinishedBoundaryTest {
  private val track =
    MediaItemTrack(
      id = "3001",
      parentKey = "1001",
      title = "Track 1",
      duration = 5_000L,
      index = 1,
    )

  /**
   * `trackProgress > duration - TRACK_FINISHED_WINDOW_MILLIS`, i.e. > 4000 for a 5s track.
   *
   * The book defaults keep the *book* far from its end so the track assertions are not polluted by
   * it. Worth stating because the obvious defaults are a trap: a 60s book at progress 0 is
   * `60000 - 0 < 120000` — **inside** the two-minute finished window, so every track test would
   * also mark the book. The book window is longer than many test fixtures are.
   */
  private fun reportAt(
    trackProgress: Long,
    bookProgress: Long = 0L,
    playbackState: String = PLEX_STATE_PAUSED,
    bookDuration: Long = 10 * 60_000L,
  ): FakeProgressApi {
    val api = FakeProgressApi()
    val reporter =
      ProgressReporter(
        api = api,
        lookupTrack = { track },
        lookupBookDuration = { bookDuration },
      )
    kotlinx.coroutines.runBlocking {
      reporter.report(
        ProgressReporter.Request(
          trackId = "3001",
          playbackState = playbackState,
          trackProgress = trackProgress,
          bookProgress = bookProgress,
        ),
      )
    }
    return api
  }

  @Test
  fun `a track well short of the end is not marked watched`() =
    runTest {
      assertTrue(reportAt(trackProgress = 1_000L).watchedKeys.isEmpty())
    }

  /**
   * Exactly on the boundary. The comparison is strictly greater-than, so 4000 of a 5000ms track
   * must **not** mark it — this is the mutant PIT called "changed conditional boundary".
   */
  @Test
  fun `a track exactly at the window boundary is not marked watched`() =
    runTest {
      assertEquals(emptyList<String>(), reportAt(trackProgress = 4_000L).watchedKeys)
    }

  @Test
  fun `a track one millisecond past the boundary is marked watched`() =
    runTest {
      assertEquals(listOf("3001"), reportAt(trackProgress = 4_001L).watchedKeys)
    }

  @Test
  fun `a track at its full duration is marked watched`() =
    runTest {
      assertTrue(reportAt(trackProgress = 5_000L).watchedKeys.contains("3001"))
    }

  /**
   * The book is marked only when playback has *ended*. Playing through the last two minutes must
   * not mark it — that is cu-9's bug, where marking watched reset the position mid-listen.
   */
  @Test
  fun `a book near its end is not marked while still playing`() =
    runTest {
      val api =
        reportAt(
          trackProgress = 100L,
          bookProgress = 10 * 60_000L - 1_000L,
          playbackState = PLEX_STATE_PLAYING,
        )

      assertTrue(
        "marking a book watched resets its position; doing it mid-playback loses the place",
        api.watchedKeys.none { it == "1001" },
      )
    }

  @Test
  fun `a book near its end is marked once playback is paused`() =
    runTest {
      val api =
        reportAt(
          trackProgress = 100L,
          bookProgress = 10 * 60_000L - 1_000L,
          playbackState = PLEX_STATE_PAUSED,
        )

      assertTrue(api.watchedKeys.contains("1001"))
    }

  @Test
  fun `a book near its end is marked once playback is stopped`() =
    runTest {
      val api =
        reportAt(
          trackProgress = 100L,
          bookProgress = 10 * 60_000L - 1_000L,
          playbackState = PLEX_STATE_STOPPED,
        )

      assertTrue(api.watchedKeys.contains("1001"))
    }

  /**
   * `bookDuration - bookProgress < BOOK_FINISHED_END_OFFSET_MILLIS` (2 minutes). With a 10-minute
   * book, 8 minutes in leaves exactly 2 minutes remaining — strictly-less-than, so not finished.
   * This is the "Replaced long subtraction with addition" mutant: with `+` the comparison is
   * nonsense for every input, and nothing noticed.
   */
  @Test
  fun `a book exactly two minutes from the end is not marked watched`() =
    runTest {
      val api =
        reportAt(
          trackProgress = 100L,
          bookProgress = 8 * 60_000L,
          bookDuration = 10 * 60_000L,
        )

      assertEquals(emptyList<String>(), api.watchedKeys.filter { it == "1001" })
    }

  @Test
  fun `a book just inside two minutes from the end is marked watched`() =
    runTest {
      val api =
        reportAt(
          trackProgress = 100L,
          bookProgress = 8 * 60_000L + 1,
          bookDuration = 10 * 60_000L,
        )

      assertTrue(api.watchedKeys.contains("1001"))
    }

  /** Half way through a long book must never be treated as finished. */
  @Test
  fun `a book half way through is not marked watched`() =
    runTest {
      val api =
        reportAt(
          trackProgress = 100L,
          bookProgress = 5 * 60_000L,
          bookDuration = 10 * 60_000L,
        )

      assertTrue(api.watchedKeys.none { it == "1001" })
    }
}
