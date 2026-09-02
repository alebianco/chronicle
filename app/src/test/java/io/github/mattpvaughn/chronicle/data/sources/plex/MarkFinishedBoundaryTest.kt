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
   * The scrobble must fire **once**, not on every progress report (cu-73).
   *
   * Plex's `/:/scrobble` increments `viewCount` rather than setting a flag, and it clears
   * `viewOffset` as a side effect. Progress is reported every ten ticks during playback, and these
   * books are often a *single* multi-hour file — so once playback passed the track's final second,
   * every later report re-fired it. The owner's real library showed `viewCount` of 183, 129 and 126
   * on single tracks of books played at most a few times, and a book he was **still listening to**
   * had `viewOffset = 0` because the last scrobble wiped his position.
   */
  @Test
  fun `an already-watched track is not scrobbled again`() {
    val api = FakeProgressApi()

    reportAt(
      trackProgress = 10 * 60_000L,
      trackViewCount = 1L,
      api = api,
    )

    assertEquals(
      "re-scrobbling inflates viewCount and destroys the listener's position",
      0,
      api.watchedCalls,
    )
  }

  /** The first pass must still mark it — the guard suppresses repeats, not the initial scrobble. */
  @Test
  fun `an unwatched track at the end is scrobbled once`() {
    val api = FakeProgressApi()

    reportAt(trackProgress = 10 * 60_000L, trackViewCount = 0L, api = api)

    assertEquals(1, api.watchedCalls)
  }

  /** Same one-shot rule for the book: repeated pauses near the end must not scrobble repeatedly. */
  @Test
  fun `an already-watched book is not scrobbled again`() {
    val api = FakeProgressApi()

    reportAt(
      trackProgress = 0L,
      bookProgress = 10 * 60_000L,
      bookDuration = 10 * 60_000L,
      bookViewCount = 2L,
      api = api,
    )

    assertEquals(0, api.watchedCalls)
  }

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
    // Unfinished by default, so the existing boundary cases still exercise a first scrobble.
    bookViewCount: Long = 0L,
    trackViewCount: Long = 0L,
    api: FakeProgressApi = FakeProgressApi(),
  ): FakeProgressApi {
    val reporter =
      ProgressReporter(
        api = api,
        lookupTrack = { track.copy(viewCount = trackViewCount) },
        lookupBookDuration = { bookDuration },
        lookupBookViewCount = { bookViewCount },
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

  /**
   * A duration of zero means "the tracks are not loaded", not "the book is over".
   *
   * Without a guard the window check is trivially true — `0 - 3000 < 120000` — so a book barely
   * started gets scrobbled finished on the server: `viewCount` incremented and `viewOffset`
   * cleared, which is precisely the damage cu-73 observed and cu-98 had to repair.
   *
   * It is reachable, not hypothetical. `lookupBookDuration` derives from
   * `getTracksForAudiobookAsync`, whose query filters `cached >= :offlineMode` — so an *uncached*
   * book in offline mode returns no tracks and therefore no duration. `Audiobook.isCompleted()`
   * has carried this guard all along; the two paths that write to the server did not.
   */
  @Test
  fun `a book whose duration is not loaded is never marked watched`() =
    runTest {
      val api =
        reportAt(
          trackProgress = 100L,
          bookProgress = 3_000L,
          bookDuration = 0L,
        )

      assertTrue(
        "duration 0 means tracks are unloaded, not that the book is finished",
        api.watchedKeys.none { it == "1001" },
      )
    }

  /** The same at the very start, which is the state a fresh play is in. */
  @Test
  fun `an unstarted book with no duration is never marked watched`() =
    runTest {
      val api =
        reportAt(
          trackProgress = 0L,
          bookProgress = 0L,
          bookDuration = 0L,
        )

      assertTrue(api.watchedKeys.none { it == "1001" })
    }
}
