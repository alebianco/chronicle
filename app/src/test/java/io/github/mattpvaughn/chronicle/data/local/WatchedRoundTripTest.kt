package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.getProgress
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Marking a book played, and un-marking it, as a round trip.
 *
 * The pair was asymmetrical: `setWatched` marked the book *and* its tracks, `setUnwatched` only the
 * book — so the tracks kept `viewCount` and their timestamps, and which state the UI showed depended
 * on what had run last. `markTracksInBookAsUnwatched` was added as the inverse in cu-86 and had **no
 * test**, which is what this closes.
 *
 * The property that matters most is the last one: the values these write must not make
 * `getActiveTrack` believe the listener is part way through the book. That is the exact regression
 * cu-90's furthest-started rule introduced and cu-86 fixed, and it is only visible when the two are
 * tested *together*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchedRoundTripTest {
  private val tracks =
    listOf(
      MediaItemTrack(id = "1", parentKey = "1001", index = 1, duration = 1_000L, progress = 300L, lastViewedAt = 100L),
      MediaItemTrack(id = "2", parentKey = "1001", index = 2, duration = 2_000L, progress = 700L, lastViewedAt = 200L),
      MediaItemTrack(id = "3", parentKey = "1001", index = 3, duration = 3_000L),
    )

  private fun repository(
    dao: TrackDao,
    scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
  ) = TrackRepository(
    trackDao = dao,
    prefsRepo = mockk(relaxed = true),
    plexMediaService = mockk<PlexMediaService>(relaxed = true),
    plexPrefs = mockk<PlexPrefsRepo>(relaxed = true),
    dispatchers = TestDispatcherProvider(scheduler),
  )

  private fun daoReturning(rows: List<MediaItemTrack>): Pair<TrackDao, () -> List<MediaItemTrack>> {
    val captured = slot<List<MediaItemTrack>>()
    val dao =
      mockk<TrackDao>(relaxed = true) {
        coEvery { getTracksForAudiobookAsync(any(), any()) } returns rows
        coEvery { insertAll(capture(captured)) } returns Unit
      }
    return dao to { if (captured.isCaptured) captured.captured else emptyList() }
  }

  @Test
  fun `marking watched sets viewCount on every track`() =
    runTest {
      val (dao, written) = daoReturning(tracks)

      repository(dao, testScheduler).markTracksInBookAsWatched("1001")

      assertEquals(listOf(1L, 1L, 1L), written().map { it.viewCount })
    }

  @Test
  fun `marking watched resets every track's position`() =
    runTest {
      val (dao, written) = daoReturning(tracks)

      repository(dao, testScheduler).markTracksInBookAsWatched("1001")

      assertEquals(listOf(0L, 0L, 0L), written().map { it.progress })
    }

  @Test
  fun `marking unwatched clears viewCount on every track`() =
    runTest {
      val watched = tracks.map { it.copy(viewCount = 1L, progress = 0L, lastViewedAt = 9_000L) }
      val (dao, written) = daoReturning(watched)

      repository(dao, testScheduler).markTracksInBookAsUnwatched("1001")

      assertEquals(listOf(0L, 0L, 0L), written().map { it.viewCount })
    }

  /**
   * The timestamp must be cleared too. "Listened just now with no progress" would win every
   * subsequent merge against the server and keep re-clearing a position set on another device.
   */
  @Test
  fun `marking unwatched clears the timestamps, not just the counts`() =
    runTest {
      val watched = tracks.map { it.copy(viewCount = 1L, progress = 0L, lastViewedAt = 9_000L) }
      val (dao, written) = daoReturning(watched)

      repository(dao, testScheduler).markTracksInBookAsUnwatched("1001")

      assertEquals(listOf(0L, 0L, 0L), written().map { it.lastViewedAt })
    }

  @Test
  fun `unwatched is the exact inverse of watched`() =
    runTest {
      val (watchDao, watched) = daoReturning(tracks)
      repository(watchDao, testScheduler).markTracksInBookAsWatched("1001")

      val (unwatchDao, unwatched) = daoReturning(watched())
      repository(unwatchDao, testScheduler).markTracksInBookAsUnwatched("1001")

      assertTrue(
        "no trace of the mark should survive un-marking",
        unwatched().all { it.viewCount == 0L && it.progress == 0L && it.lastViewedAt == 0L },
      )
    }

  /**
   * The regression that connects cu-86 to cu-90. Every track marked watched carries
   * `lastViewedAt = now`; if that counted as "started", `getActiveTrack` would return the **last**
   * track and the book would report itself part way through — 3000ms of 6000 for these tracks —
   * instead of at the start.
   */
  @Test
  fun `a book marked as read does not report a position part way through`() =
    runTest {
      val (dao, written) = daoReturning(tracks)

      repository(dao, testScheduler).markTracksInBookAsWatched("1001")

      assertEquals(
        "marking a book read must not invent a position inside it",
        0L,
        written().getProgress(),
      )
    }
}
