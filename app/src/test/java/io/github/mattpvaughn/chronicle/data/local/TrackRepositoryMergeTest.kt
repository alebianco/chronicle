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
}
