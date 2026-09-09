package io.github.mattpvaughn.chronicle.features.bookdetails

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.BookProgressState
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.github.mattpvaughn.chronicle.util.keepCollected
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * First tests for `AudiobookDetailsViewModel`, which sat at 0% instruction coverage while owning
 * the screen that shows a book's progress and completion state.
 *
 * Nothing about the class prevented testing — the blocker was `Dispatchers.Main`, which
 * `asLiveData()` touches during construction. [MainDispatcherRule] pays that once. The two final
 * classes it needs (`MediaServiceConnection`, `PlexConfig`) are mocked rather than replaced with
 * extracted interfaces: MockK handles final classes on the JVM, and a speculative refactor across
 * 653 lines would risk far more than it buys.
 */
class AudiobookDetailsViewModelTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  private val book = Audiobook(id = "1001", source = TEST_SOURCE, title = "Dune")
  private val tracksFlow = MutableStateFlow<List<MediaItemTrack>>(emptyList())

  private val bookRepository =
    mockk<IBookRepository>(relaxed = true) {
      every { getAudiobook("1001") } returns MutableStateFlow(book)
    }

  private val trackRepository =
    mockk<ITrackRepository>(relaxed = true) {
      every { getTracksForAudiobook("1001") } returns tracksFlow
    }

  private val plexConfig =
    mockk<PlexConfig>(relaxed = true) {
      every { isConnected } returns MutableStateFlow(true)
      // `connectionState` needs a real flow as well, and for a sharper reason than `isConnected`
      // did: it is one of `uiState`'s four sources, and `combine` emits nothing until **every**
      // source has produced. A relaxed mock hands back a mocked StateFlow that never emits, so
      // `uiState` silently stays on its seed — every field default, no error, no failing
      // assertion unless a test happens to read one. That is what made a wired-up progress line
      // look like a broken formatter.
      every { connectionState } returns MutableStateFlow(PlexConfig.ConnectionState.CONNECTED)
    }

  /**
   * `nowPlaying` must be stubbed explicitly: a relaxed mock returns a plain Object for it, and
   * `updateProgressIfChangingBook` casts it to MediaMetadataCompat.
   *
   * A mock rather than the real `NOTHING_PLAYING`: that constant is built by
   * `MediaMetadataCompat.Builder`, which is an Android framework class stubbed to throw in a JVM
   * unit test, so merely referencing it fails the whole class with ExceptionInInitializerError.
   */
  private val mediaServiceConnection =
    mockk<MediaServiceConnection>(relaxed = true) {
      every { nowPlaying } returns MutableStateFlow(mockk<MediaMetadataCompat>(relaxed = true))
      // `isConnected` needs a real flow too: a relaxed mock hands back a mocked StateFlow whose
      // `value` is a plain Object, and the production read is now a direct Boolean rather than the
      // null-tolerant `== true` the LiveData version used.
      every { isConnected } returns MutableStateFlow(false)
    }

  @Test
  fun `the view model can be constructed without a device`() {
    assertNotNull(viewModel())
  }

  /**
   * Caching needs the server: without it the user is told, and no download is started.
   *
   * `cacheStatus` is a [io.github.mattpvaughn.chronicle.util.DoubleLiveData], and a
   * MediatorLiveData only pulls from its sources while it has an active observer — so the test has
   * to observe it, or `cacheStatus.value` is null and `onCacheButtonClick` throws
   * NoWhenBranchMatchedException.
   */
  @Test
  fun `caching while disconnected tells the user instead of starting a download`() =
    runTest {
      every { plexConfig.isConnected } returns MutableStateFlow(false)
      val cachedFileManager = cacheManager()
      val viewModel = viewModel(cachedFileManager = cachedFileManager)
      keepCollected(viewModel.cacheStatus)

      viewModel.onCacheButtonClick()

      verify(exactly = 0) { cachedFileManager.downloadTracks(any(), any()) }
    }

  /** Connected, and the book is not yet downloaded: the download must actually start. */
  @Test
  fun `caching while connected starts the download`() =
    runTest {
      every { plexConfig.isConnected } returns MutableStateFlow(true)
      val cachedFileManager = cacheManager()
      val viewModel = viewModel(cachedFileManager = cachedFileManager)
      keepCollected(viewModel.cacheStatus)

      viewModel.onCacheButtonClick()

      verify { cachedFileManager.downloadTracks("1001", "Dune") }
    }

  /** Pressing play with no server and no download must not silently do nothing. */
  @Test
  fun `playing an undownloaded book while disconnected does not reach the player`() =
    runTest {
      every { plexConfig.isConnected } returns MutableStateFlow(false)

      val viewModel = viewModel()
      // `audiobook` is `stateIn(Eagerly)`, but the eager collector is *scheduled* on the test
      // dispatcher rather than run — without this the guard reads the `null` seed and lets an
      // uncached book through with no server.
      advanceUntilIdle()
      viewModel.pausePlayButtonClicked()

      verify(exactly = 0) { mediaServiceConnection.connect(any()) }
    }

  /** A cached book stays playable with no server — the offline case the app exists to support. */
  @Test
  fun `playing a downloaded book while disconnected still reaches the player`() {
    every { plexConfig.isConnected } returns MutableStateFlow(false)
    every { bookRepository.getAudiobook("1001") } returns
      MutableStateFlow(book.copy(isCached = true))
    every { mediaServiceConnection.isConnected } returns MutableStateFlow(false)

    viewModel().pausePlayButtonClicked()

    verify { mediaServiceConnection.connect(any()) }
  }

  /** Already connected: no reconnect, the action runs directly. */
  @Test
  fun `playing while already connected does not reconnect`() {
    every { mediaServiceConnection.isConnected } returns MutableStateFlow(true)
    every { mediaServiceConnection.transportControls } returns null

    viewModel().pausePlayButtonClicked()

    verify(exactly = 0) { mediaServiceConnection.connect(any()) }
  }

  /** A force sync with no server must report that, not attempt a fetch. */
  @Test
  fun `force syncing while disconnected does not touch the repository`() {
    every { plexConfig.isConnected } returns MutableStateFlow(false)

    viewModel().forceSyncBook(hasUserConfirmation = true)

    coVerify(exactly = 0) { trackRepository.syncTracksInBook(any(), any()) }
  }

  /**
   * Pressing cache *while caching* cancels the download rather than starting a second one.
   * `cacheStatus` is driven through its real input (`activeBookDownloads`), not stubbed directly —
   * the derivation is part of what is under test.
   */
  @Test
  fun `pressing cache while a download is running cancels it`() =
    runTest {
      val cachedFileManager =
        mockk<ICachedFileManager>(relaxed = true) {
          every { activeBookDownloads } returns MutableStateFlow(setOf("1001"))
        }
      val viewModel = viewModel(cachedFileManager = cachedFileManager)
      keepCollected(viewModel.cacheStatus)

      viewModel.onCacheButtonClick()

      verify(exactly = 1) { cachedFileManager.cancelGroup("1001") }
      verify(exactly = 0) { cachedFileManager.downloadTracks(any(), any()) }
    }

  /** A download for a *different* book must not make this one look like it is caching. */
  @Test
  fun `another book downloading does not cancel this one`() =
    runTest {
      val cachedFileManager =
        mockk<ICachedFileManager>(relaxed = true) {
          every { activeBookDownloads } returns MutableStateFlow(setOf("9999"))
        }
      val viewModel = viewModel(cachedFileManager = cachedFileManager)
      keepCollected(viewModel.cacheStatus)

      viewModel.onCacheButtonClick()

      verify(exactly = 0) { cachedFileManager.cancelGroup(any()) }
      verify { cachedFileManager.downloadTracks("1001", "Dune") }
    }

  /**
   * Pressing cache on an already-downloaded book starts no download and asks before deleting.
   * Deleting a download without confirmation would be a data-loss action on a single tap.
   */
  @Test
  fun `pressing cache on a downloaded book prompts instead of downloading`() =
    runTest {
      every { bookRepository.getAudiobook("1001") } returns
        MutableStateFlow(book.copy(isCached = true))
      val cachedFileManager = cacheManager()
      val viewModel = viewModel(cachedFileManager = cachedFileManager)
      keepCollected(viewModel.cacheStatus)

      viewModel.onCacheButtonClick()

      verify(exactly = 0) { cachedFileManager.downloadTracks(any(), any()) }
      coVerify(exactly = 0) { cachedFileManager.deleteCachedBook(any()) }
      assertTrue(
        "deleting a download must be confirmed, not done on one tap",
        viewModel.bottomChooserState.value?.shouldShow == true,
      )
    }

  /**
   * Behaviour 2: jump-to-chapter warns before clearing progress. Without confirmation it must
   * show the prompt and reach neither the player nor the connection.
   */
  @Test
  fun `jumping to a chapter asks before clearing progress`() {
    every { mediaServiceConnection.isConnected } returns MutableStateFlow(true)
    val viewModel = viewModel()

    viewModel.jumpToChapter(bookStartTimeOffset = BookOffset(5_000L), trackId = "2001")

    assertTrue(
      "a jump discards the current position, so it must be confirmed",
      viewModel.bottomChooserState.value?.shouldShow == true,
    )
    verify(exactly = 0) { mediaServiceConnection.transportControls }
  }

  /** With confirmation it proceeds, connecting first when the service is not up. */
  @Test
  fun `a confirmed jump connects and plays`() {
    every { mediaServiceConnection.isConnected } returns MutableStateFlow(false)
    val viewModel = viewModel()

    viewModel.jumpToChapter(bookStartTimeOffset = BookOffset(5_000L), trackId = "2001", hasUserConfirmation = true)

    verify { mediaServiceConnection.connect(any()) }
  }

  /**
   * A press before `cacheStatus` resolves must do nothing, not crash.
   *
   * `cacheStatus` is a MediatorLiveData with no value until it has an active observer *and* both
   * sources have emitted — so this test deliberately does **not** observe it. It previously threw
   * NoWhenBranchMatchedException, an uncaught exception on a main-screen control.
   */
  @Test
  fun `pressing cache before the status resolves does nothing`() {
    val cachedFileManager = cacheManager()

    viewModel(cachedFileManager = cachedFileManager).onCacheButtonClick()

    verify(exactly = 0) { cachedFileManager.downloadTracks(any(), any()) }
    verify(exactly = 0) { cachedFileManager.cancelGroup(any()) }
  }

  /**
   * The progress line reaches the screen as **millis**, and they are the tracks' real numbers.
   *
   * This exists because the unit test for the formatter passed while the screen rendered a blank
   * length: the formatter was right and what reached it was not. `DetailsProgressTextTest` covers
   * the wording; this covers the wiring, which is the half that was actually broken.
   */
  @Test
  fun `the progress line carries the tracks own duration and position`() =
    runTest {
      val vm = viewModel()
      tracksFlow.value =
        listOf(
          MediaItemTrack(id = "1", parentKey = "1001", index = 1, duration = 3_600_000L, progress = 600_000L),
          MediaItemTrack(id = "2", parentKey = "1001", index = 2, duration = 3_600_000L),
        )

      keepCollected(vm.uiState)
      advanceUntilIdle()

      val progress = vm.uiState.value.progress
      assertEquals(7_200_000L, progress.durationMillis)
      assertEquals(600_000L, progress.progressMillis)
      assertEquals("8%", progress.percentage)
    }

  /**
   * The case that was blank on the tablet: a book the user has never opened, so its tracks have not
   * been fetched, but whose **book row** already carries duration and progress from the library
   * sync.
   *
   * Reading only the tracks made the readout empty on every unopened book — the common case on this
   * screen, and precisely the state the "not started" wording was added for.
   */
  @Test
  fun `the progress line falls back to the book row while the tracks are unfetched`() =
    runTest {
      every { bookRepository.getAudiobook("1001") } returns
        MutableStateFlow(book.copy(duration = 540_000L, progress = 54_000L))
      val vm = viewModel()

      keepCollected(vm.uiState)
      advanceUntilIdle()

      val progress = vm.uiState.value.progress
      assertEquals(540_000L, progress.durationMillis)
      assertEquals(54_000L, progress.progressMillis)
      // And the percentage comes from those same two numbers, rather than from a second read of
      // the empty track list -- which showed `7m left` beside `0%` on the tablet.
      assertEquals("10%", progress.percentage)
    }

  /**
   * And once the tracks arrive they win, because they are what playback advances — the book row's
   * copy is written by sync and lags mid-listen.
   */
  @Test
  fun `the tracks take precedence over the book row once they load`() =
    runTest {
      every { bookRepository.getAudiobook("1001") } returns
        MutableStateFlow(book.copy(duration = 540_000L, progress = 54_000L))
      val vm = viewModel()

      keepCollected(vm.uiState)
      tracksFlow.value =
        listOf(MediaItemTrack(id = "1", parentKey = "1001", index = 1, duration = 7_200_000L, progress = 600_000L))
      advanceUntilIdle()

      val progress = vm.uiState.value.progress
      assertEquals(7_200_000L, progress.durationMillis)
      assertEquals(600_000L, progress.progressMillis)
    }

  /**
   * The state travels with the numbers, and it comes from the **book** — `viewCount` lives only
   * there. A book marked as played sits at `progress = 0` on both the tracks and the row, so
   * deriving the state from position alone rendered it as never-opened (decision-16).
   */
  @Test
  fun `a book marked as played reaches the screen as completed`() =
    runTest {
      every { bookRepository.getAudiobook("1001") } returns
        MutableStateFlow(book.copy(duration = 540_000L, progress = 0L, viewCount = 1L))
      val vm = viewModel()

      keepCollected(vm.uiState)
      advanceUntilIdle()

      assertEquals(BookProgressState.Completed, vm.uiState.value.progress.state)
    }

  /** And an empty book stays at zero, which the formatter renders blank rather than as `0m`. */
  @Test
  fun `the progress line is zero while the tracks are empty`() =
    runTest {
      val vm = viewModel()

      keepCollected(vm.uiState)
      advanceUntilIdle()

      assertEquals(0L, vm.uiState.value.progress.durationMillis)
    }

  private fun cacheManager() =
    mockk<ICachedFileManager>(relaxed = true) {
      every { activeBookDownloads } returns MutableStateFlow(emptySet())
    }

  private fun viewModel(cachedFileManager: ICachedFileManager = cacheManager()) =
    AudiobookDetailsViewModel(
      bookRepository = bookRepository,
      trackRepository = trackRepository,
      cachedFileManager = cachedFileManager,
      mediaServiceConnection = mediaServiceConnection,
      plexConfig = plexConfig,
      plexMediaService = mockk<PlexMediaService>(relaxed = true),
      currentlyPlaying = mockk<CurrentlyPlaying>(relaxed = true),
      appContext = mockk<Context>(relaxed = true),
      dispatchers = TestDispatcherProvider(),
      // The id travels as an ordinary constructor argument, which is the same path production
      // takes: Circuit's presenter factory reads it off the screen key and passes it here.
      bookId = book.id,
    )
}
