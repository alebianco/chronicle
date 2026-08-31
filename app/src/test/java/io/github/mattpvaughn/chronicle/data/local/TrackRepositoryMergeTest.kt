package io.github.mattpvaughn.chronicle.data.local

import com.github.michaelbull.result.getError
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexPrefsRepo
import io.github.mattpvaughn.chronicle.data.sources.plex.model.Media
import io.github.mattpvaughn.chronicle.data.sources.plex.model.Part
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexDirectory
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainer
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexMediaContainerWrapper
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * `TrackRepository.loadTracksForAudiobook` and the merge behind it.
 *
 * At 15% instruction coverage this was one of the least-tested pieces of the trust surface, while
 * owning two things the owner reported as broken:
 *
 * 1. **Listening position across devices.** The merge is where a network track meets the local one,
 *    and decision-16 makes the *tracks* the sole owner of position. `mergeNetworkTracks` had no
 *    test at all, so nothing pinned that it delegates to [MediaItemTrack.merge] rather than
 *    overwriting.
 * 2. **A downloaded book that stops resolving.** A cached file is named `<trackId>.<ext>`, so when
 *    Plex changes a track's ratingKey the download is orphaned and the book reports itself
 *    uncached — the owner's *"book reports no cache even if I'm sure I have downloaded it"*. The
 *    repository detects this by matching (parentKey, title, duration) and renames the file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackRepositoryMergeTest {
  @get:Rule
  val folder = TemporaryFolder()

  private val trackDao = mockk<TrackDao>(relaxed = true)
  private val plexMediaService = mockk<PlexMediaService>(relaxed = true)
  private val plexPrefsRepo = mockk<PlexPrefsRepo>(relaxed = true)

  private val prefsRepo =
    mockk<PrefsRepo>(relaxed = true) {
      every { offlineMode } returns false
    }

  /** A network track, as it arrives from Plex. */
  private fun networkTrack(
    id: String,
    title: String = "Chapter One",
    duration: Long = 10_000L,
    viewOffset: Long = 0L,
    lastViewedAtSeconds: Long = 0L,
    parent: Int = 1001,
  ) = PlexDirectory(
    ratingKey = id,
    parentRatingKey = parent,
    title = title,
    duration = duration,
    viewOffset = viewOffset,
    lastViewedAt = lastViewedAtSeconds,
    index = 1,
    media = listOf(Media(part = listOf(Part(key = "/library/parts/$id/file.mp3", size = 1)))),
  )

  private fun localTrack(
    id: String,
    title: String = "Chapter One",
    duration: Long = 10_000L,
    progress: Long = 0L,
    lastViewedAt: Long = 0L,
    parent: String = "1001",
    cached: Boolean = false,
  ) = MediaItemTrack(
    id = id,
    parentKey = parent,
    title = title,
    duration = duration,
    progress = progress,
    lastViewedAt = lastViewedAt,
    index = 1,
    media = "/library/parts/$id/file.mp3",
    cached = cached,
  )

  private fun repository() =
    TrackRepository(
      trackDao = trackDao,
      prefsRepo = prefsRepo,
      plexMediaService = plexMediaService,
      plexPrefs = plexPrefsRepo,
      dispatchers = TestDispatcherProvider(TestScope().testScheduler),
    )

  private fun serverReturns(vararg tracks: PlexDirectory) {
    coEvery { plexMediaService.retrieveTracksForAlbum(any()) } returns
      PlexMediaContainerWrapper(PlexMediaContainer(metadata = tracks.toList()))
  }

  /** What the repository actually wrote to the database. */
  private fun captureInserted(): List<MediaItemTrack> {
    val inserted = slot<List<MediaItemTrack>>()
    coVerify { trackDao.insertAll(capture(inserted)) }
    return inserted.captured
  }

  /**
   * The decision-16 rule, at the repository level: a local position newer than the server's must
   * survive a sync. This is the owner's *"same account on different devices reports WILDLY
   * different positions"* — the merge is where that is decided.
   */
  @Test
  fun `a newer local position survives a sync`() =
    runTest {
      coEvery { trackDao.getAllTracksAsync() } returns
        listOf(localTrack(id = "2001", progress = 8_000L, lastViewedAt = 9_000L))
      // Plex reports seconds; 3 seconds is older than the local 9_000ms.
      serverReturns(networkTrack(id = "2001", viewOffset = 500L, lastViewedAtSeconds = 3L))

      repository().loadTracksForAudiobook("1001", forceUseNetwork = false)

      assertEquals(
        "the newer local position must not be overwritten by a stale server offset",
        8_000L,
        captureInserted().single().progress,
      )
    }

  /** The other direction: a genuinely newer server position is adopted. */
  @Test
  fun `a newer network position is adopted`() =
    runTest {
      coEvery { trackDao.getAllTracksAsync() } returns
        listOf(localTrack(id = "2001", progress = 500L, lastViewedAt = 3_000L))
      serverReturns(networkTrack(id = "2001", viewOffset = 8_000L, lastViewedAtSeconds = 9L))

      repository().loadTracksForAudiobook("1001", forceUseNetwork = false)

      assertEquals(8_000L, captureInserted().single().progress)
    }

  /** Forcing the network wins regardless of which side is newer. */
  @Test
  fun `forcing the network overrides a newer local position`() =
    runTest {
      coEvery { trackDao.getAllTracksAsync() } returns
        listOf(localTrack(id = "2001", progress = 8_000L, lastViewedAt = 9_000L))
      serverReturns(networkTrack(id = "2001", viewOffset = 500L, lastViewedAtSeconds = 3L))

      repository().loadTracksForAudiobook("1001", forceUseNetwork = true)

      assertEquals(
        "forceUseNetwork must reach MediaItemTrack.merge, not be dropped on the way",
        500L,
        captureInserted().single().progress,
      )
    }

  /** A track the local DB has never seen is inserted as-is. */
  @Test
  fun `a brand new track is kept`() =
    runTest {
      coEvery { trackDao.getAllTracksAsync() } returns emptyList()
      serverReturns(networkTrack(id = "2001"), networkTrack(id = "2002"))

      repository().loadTracksForAudiobook("1001", forceUseNetwork = false)

      assertEquals(listOf("2001", "2002"), captureInserted().map { it.id })
    }

  /**
   * The orphaned-download case. Plex reassigned the ratingKey, so `2001.mp3` on disk no longer
   * matches the track's new id and the book reads as uncached. The repository matches on
   * (parentKey, title, duration) and renames the file to follow the new id.
   */
  @Test
  fun `a track whose id changed has its downloaded file renamed`() =
    runTest {
      val cacheDir = folder.newFolder("cache")
      every { prefsRepo.cachedMediaDir } returns cacheDir
      File(cacheDir, "2001.mp3").writeText("audio")

      coEvery { trackDao.getAllTracksAsync() } returns
        listOf(localTrack(id = "2001", title = "Chapter One", duration = 10_000L, cached = true))
      // Same book, title and duration — but a new ratingKey.
      serverReturns(networkTrack(id = "9001", title = "Chapter One", duration = 10_000L))

      repository().loadTracksForAudiobook("1001", forceUseNetwork = false)

      assertTrue(
        "the download must follow the new id, or the book reports itself uncached",
        File(cacheDir, "9001.mp3").exists(),
      )
      assertTrue("the stale name must not linger", !File(cacheDir, "2001.mp3").exists())
    }

  /** A genuinely different track must not steal another's download. */
  @Test
  fun `a different track does not trigger a rename`() =
    runTest {
      val cacheDir = folder.newFolder("cache")
      every { prefsRepo.cachedMediaDir } returns cacheDir
      File(cacheDir, "2001.mp3").writeText("audio")

      coEvery { trackDao.getAllTracksAsync() } returns
        listOf(localTrack(id = "2001", title = "Chapter One", duration = 10_000L, cached = true))
      serverReturns(networkTrack(id = "9001", title = "Chapter Two", duration = 44_000L))

      repository().loadTracksForAudiobook("1001", forceUseNetwork = false)

      assertTrue(
        "an unrelated track must leave the existing download alone",
        File(cacheDir, "2001.mp3").exists(),
      )
      assertTrue(!File(cacheDir, "9001.mp3").exists())
    }

  /**
   * A failed fetch must not be reported as success with an empty list — that would write nothing
   * and, worse, read as "this book has no tracks".
   */
  @Test
  fun `a network failure is an error, not an empty success`() =
    runTest {
      coEvery { trackDao.getAllTracksAsync() } returns listOf(localTrack(id = "2001"))
      coEvery { plexMediaService.retrieveTracksForAlbum(any()) } throws IOException("offline")

      val result = repository().loadTracksForAudiobook("1001", forceUseNetwork = false)

      assertTrue("a failed fetch must surface as an error", result.getError() != null)
      coVerify(exactly = 0) { trackDao.insertAll(any()) }
    }

  /**
   * Mark-as-read writes three fields per track, and each one is a decision (cu-86, cu-90):
   * `progress = 0` because a finished book is not part-way through, `viewCount = 1` because
   * completion is an explicit fact rather than something inferred from position (decision-16), and
   * `lastViewedAt = now` so the local state wins the next merge against the server.
   */
  @Test
  fun `marking a book read zeroes progress and records the view`() =
    runTest {
      coEvery { trackDao.getTracksForAudiobookAsync("1001", any()) } returns
        listOf(
          localTrack(id = "2001", progress = 900L),
          localTrack(id = "2002", progress = 4_000L),
        )

      repository().markTracksInBookAsWatched("1001")

      val written = captureInserted()
      assertEquals("every track resets to the start", listOf(0L, 0L), written.map { it.progress })
      assertEquals("completion is explicit, not inferred", listOf(1L, 1L), written.map { it.viewCount })
      assertTrue("the local write must win the next merge", written.all { it.lastViewedAt > 0L })
    }

  /**
   * Unread is not simply the inverse: `lastViewedAt` is cleared too. Leaving it set would read as
   * "listened to just now with no progress", which wins every subsequent merge against the server
   * and keeps re-clearing a position set on another device.
   */
  @Test
  fun `marking a book unread clears the timestamp as well as the position`() =
    runTest {
      coEvery { trackDao.getTracksForAudiobookAsync("1001", any()) } returns
        listOf(localTrack(id = "2001", progress = 4_000L, lastViewedAt = 9_000L))

      repository().markTracksInBookAsUnwatched("1001")

      val written = captureInserted().single()
      assertEquals(0L, written.progress)
      assertEquals(0L, written.viewCount)
      assertEquals(
        "a stale timestamp here re-clears a position set on another device",
        0L,
        written.lastViewedAt,
      )
    }

  /** A book with no tracks must not write anything, rather than failing. */
  @Test
  fun `marking a book with no tracks writes an empty list`() =
    runTest {
      coEvery { trackDao.getTracksForAudiobookAsync("1001", any()) } returns emptyList()

      repository().markTracksInBookAsWatched("1001")

      assertTrue(captureInserted().isEmpty())
    }
}
