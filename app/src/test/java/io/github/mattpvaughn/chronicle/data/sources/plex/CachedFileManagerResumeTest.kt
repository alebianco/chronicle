package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.features.download.DownloadIntentStore
import io.github.mattpvaughn.chronicle.features.download.DownloadRequest
import io.github.mattpvaughn.chronicle.features.download.Downloader
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * That a partial download is **resumed**, not deleted and restarted.
 *
 * This is the behaviour change the engine swap made possible, and it is worth its own test because
 * the old code did the opposite *deliberately*: Fetch2's request could not express "continue this
 * file", so an existing-but-uncached file was deleted before re-requesting. `KtorDownloader` sends
 * `Range: bytes=<len>-` instead, so the partial has to survive `makeRequests` for the resume to
 * happen at all.
 *
 * Getting this wrong is expensive rather than incorrect: it re-fetches a 293 MB book over a
 * connection that already failed once. Getting the *neighbouring* case wrong is worse — deleting a
 * complete file, or enqueueing one that is already cached — so both are pinned here too.
 *
 * Robolectric because the class registers a `BroadcastReceiver` in its `init` block, so
 * constructing it at all needs a real `IntentFilter` — the same reason
 * `CachedFileManagerUncacheTest` uses it.
 */
@RunWith(RobolectricTestRunner::class)
class CachedFileManagerResumeTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private val downloader = mockk<Downloader>(relaxed = true)
  private val intents = mockk<DownloadIntentStore>(relaxed = true)
  private val trackRepo = mockk<ITrackRepository>(relaxed = true)
  private val bookRepo = mockk<IBookRepository>(relaxed = true)

  private fun track(
    id: String,
    cached: Boolean,
  ) = MediaItemTrack(id = id, parentKey = BOOK_ID, media = "/library/parts/$id.mp3", cached = cached)

  /**
   * The on-disk name for [trackId], derived the way the model derives it.
   *
   * `getCachedFileName()` is `"<id>.<extension of media>"`, so a fixture whose `media` has no
   * extension writes `2001.` and never matches the file the manager looks for — which is how the
   * "already cached" test first failed against correct code.
   */
  private fun cachedFile(trackId: String) = File(tmp.root, "$trackId.mp3")

  private fun manager(scope: TestScope): CachedFileManager {
    val prefs = mockk<PrefsRepo>(relaxed = true)
    every { prefs.cachedMediaDir } returns tmp.root
    val plexConfig = mockk<PlexConfig>(relaxed = true)
    every { plexConfig.makeDownloadUrl(any()) } answers { "http://localhost${firstArg<String>()}?download=1" }
    return CachedFileManager(
      downloader = downloader,
      downloadIntents = intents,
      prefsRepo = prefs,
      trackRepository = trackRepo,
      bookRepository = bookRepo,
      plexConfig = plexConfig,
      applicationContext = mockk(relaxed = true),
      workManager = mockk(relaxed = true),
      dispatchers = TestDispatcherProvider(scope.testScheduler),
      externalScope = scope,
      externalFileDirs = listOf(tmp.root),
    )
  }

  private fun enqueuedRequests(scope: TestScope): List<DownloadRequest> {
    val captured = slot<List<DownloadRequest>>()
    coVerify { downloader.enqueue(capture(captured)) }
    return captured.captured
  }

  @Test
  fun `an uncached file already on disk is kept and re-requested for resume`() =
    runTest {
      // The partial. Uncached in the database, present on disk — a download that was interrupted.
      val partial = cachedFile("2001")
      partial.writeText("PARTIAL BYTES")
      coEvery { trackRepo.getTracksForAudiobookAsync(BOOK_ID) } returns listOf(track("2001", cached = false))

      manager(this).downloadTracks(BOOK_ID, "A Book")
      advanceUntilIdle()

      assertTrue(
        "the partial must survive: deleting it turns a Range request into a full re-download",
        partial.exists(),
      )
      assertEquals(
        "the bytes must be untouched so the Range offset is right",
        "PARTIAL BYTES",
        partial.readText(),
      )
      assertEquals(
        "the track must still be requested, so the downloader can resume it",
        listOf("2001"),
        enqueuedRequests(this).map { it.trackId },
      )
    }

  @Test
  fun `a track that is cached and present is not requested at all`() =
    runTest {
      // Already downloaded. Re-requesting would be wasted traffic at best, and the downloader
      // would have to rely on a 416 to notice.
      val complete = cachedFile("2001")
      complete.writeText("COMPLETE")
      coEvery { trackRepo.getTracksForAudiobookAsync(BOOK_ID) } returns listOf(track("2001", cached = true))

      manager(this).downloadTracks(BOOK_ID, "A Book")
      advanceUntilIdle()

      assertTrue("a finished download must be left alone", complete.exists())
      coVerify(exactly = 0) { downloader.enqueue(any()) }
    }

  @Test
  fun `the download intent is recorded before the downloader is called`() =
    runTest {
      // Order matters, and the safe direction is to over-record. The intent is what keeps a
      // partial safe from the cache scan's prune, so a crash between the two calls must leave the
      // bytes protected rather than orphaned.
      coEvery { trackRepo.getTracksForAudiobookAsync(BOOK_ID) } returns listOf(track("2001", cached = false))

      manager(this).downloadTracks(BOOK_ID, "A Book")
      advanceUntilIdle()

      io.mockk.coVerifyOrder {
        intents.add(listOf("2001"))
        downloader.enqueue(any())
      }
    }

  @Test
  fun `a request carries the real book id, not a hash`() =
    runTest {
      // Fetch2's grouping API was Int-only, so the book id was hashed for the group and carried
      // verbatim in an extras map because a hash cannot be reversed. Nothing here needs that.
      coEvery { trackRepo.getTracksForAudiobookAsync(BOOK_ID) } returns listOf(track("2001", cached = false))

      manager(this).downloadTracks(BOOK_ID, "A Book")
      advanceUntilIdle()

      assertEquals(BOOK_ID, enqueuedRequests(this).single().bookId)
    }

  @Test
  fun `a destination that escapes the cache directory is refused`() =
    runTest {
      // Defence in depth, carried over unchanged. This is the line that writes to the filesystem,
      // and `File(parent, child)` does not normalize — a path escaping the cache dir would land
      // next to the Room databases and the credential file.
      coEvery { trackRepo.getTracksForAudiobookAsync(BOOK_ID) } returns
        listOf(track("../../evil", cached = false))

      manager(this).downloadTracks(BOOK_ID, "A Book")
      advanceUntilIdle()

      coVerify(exactly = 0) { downloader.enqueue(any()) }
    }

  private companion object {
    const val BOOK_ID = "1234"
  }
}
