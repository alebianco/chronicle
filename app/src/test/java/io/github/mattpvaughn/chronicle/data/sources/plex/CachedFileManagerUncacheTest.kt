package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * `CachedFileManager.uncacheAllInLibrary`, which deletes real files off the device.
 *
 * `CachedFileManagerScopeTest` says driving this class "needs a real Fetch and a
 * BroadcastReceiver, which is instrumented territory" — true of the download callbacks, but not of
 * this method, which only needs the directory list. That list came from
 * `Injector.get().externalDeviceDirs()` **in a field initialiser**, so constructing the class on
 * the JVM threw before it could run any of it. It is a constructor parameter now.
 *
 * Worth testing precisely because the failure mode is silent and destructive: the filter decides
 * which files on the user's storage get deleted.
 *
 * Robolectric because the class registers a `BroadcastReceiver` in its `init` block, and
 * `IntentFilter.addAction` is one of the unmocked framework methods — the *other* barrier
 * `CachedFileManagerScopeTest` names. The download callbacks it also mentions genuinely do need a
 * real `Fetch`; this method does not.
 */
@RunWith(RobolectricTestRunner::class)
class CachedFileManagerUncacheTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  /**
   * [MediaItemTrack.getCachedFileName] is "$id.${File(media).extension}", so a track with no
   * `media` yields "101." — which does not match `cachedFilePattern` and would silently never be
   * deleted, making every assertion below pass for the wrong reason.
   */
  private fun track(
    id: String,
    parentKey: String = "book-1",
  ) = MediaItemTrack(id = id, parentKey = parentKey, media = "/library/parts/$id/file.mp3")

  private fun manager(
    dirs: List<File>,
    cached: List<MediaItemTrack>,
    trackRepo: ITrackRepository = mockk(relaxed = true),
    bookRepo: IBookRepository = mockk(relaxed = true),
  ): CachedFileManager {
    coEvery { trackRepo.getCachedTracks() } returns cached
    return CachedFileManager(
      downloader = mockk(relaxed = true),
      downloadIntents = mockk(relaxed = true),
      prefsRepo = mockk(relaxed = true),
      trackRepository = trackRepo,
      bookRepository = bookRepo,
      plexConfig = mockk(relaxed = true),
      applicationContext = mockk(relaxed = true),
      workManager = mockk(relaxed = true),
      dispatchers = TestDispatcherProvider(),
      externalScope = TestScope(),
      externalFileDirs = dirs,
    )
  }

  @Test
  fun `a cached track's file is deleted`() =
    runTest {
      val dir = temporaryFolder.newFolder("media")
      val cached = track("101")
      val file = File(dir, cached.getCachedFileName()).apply { writeText("audio") }

      manager(listOf(dir), listOf(cached)).uncacheAllInLibrary()

      assertFalse("the cached track's file should be gone", file.exists())
    }

  /**
   * A file matching the cache-name pattern but belonging to *another* library must survive. The
   * filter picks up every `cachedFilePattern` match in the directory, and only the names the track
   * repository reports for this library are deleted — switching libraries must not wipe the other.
   */
  @Test
  fun `a cached file from another library is left alone`() =
    runTest {
      val dir = temporaryFolder.newFolder("media")
      val mine = track("101")
      val theirs = track("999")
      val myFile = File(dir, mine.getCachedFileName()).apply { writeText("audio") }
      val theirFile = File(dir, theirs.getCachedFileName()).apply { writeText("audio") }

      manager(listOf(dir), listOf(mine)).uncacheAllInLibrary()

      assertFalse(myFile.exists())
      assertTrue("another library's download must survive", theirFile.exists())
    }

  /** An unrelated file in the same directory is not a cached track and must not be touched. */
  @Test
  fun `a file that is not a cached track is never deleted`() =
    runTest {
      val dir = temporaryFolder.newFolder("media")
      val unrelated = File(dir, "holiday-photo.jpg").apply { writeText("not audio") }

      manager(listOf(dir), listOf(track("101"))).uncacheAllInLibrary()

      assertTrue("an unrelated file must survive", unrelated.exists())
    }

  @Test
  fun `both repositories are told the library is no longer cached`() =
    runTest {
      val trackRepo = mockk<ITrackRepository>(relaxed = true)
      val bookRepo = mockk<IBookRepository>(relaxed = true)

      manager(listOf(temporaryFolder.newFolder("media")), emptyList(), trackRepo, bookRepo)
        .uncacheAllInLibrary()

      coVerify { trackRepo.uncacheAll() }
      coVerify { bookRepo.uncacheAll() }
    }

  /**
   * A directory that cannot be listed changes nothing.
   *
   * `listFiles` answers null for a missing or unreadable directory, and coalescing that to an empty
   * list is what un-cached whole libraries. A second, readable directory must still be processed
   * rather than the whole scan aborting.
   */
  @Test
  fun `an unreadable directory does not stop the readable ones`() =
    runTest {
      val missing = File(temporaryFolder.root, "never-created")
      val real = temporaryFolder.newFolder("media")
      val cached = track("101")
      val file = File(real, cached.getCachedFileName()).apply { writeText("audio") }

      val count = manager(listOf(missing, real), listOf(cached)).uncacheAllInLibrary()

      assertFalse(file.exists())
      assertEquals("only the readable directory's file should be counted", 1, count)
    }
}
