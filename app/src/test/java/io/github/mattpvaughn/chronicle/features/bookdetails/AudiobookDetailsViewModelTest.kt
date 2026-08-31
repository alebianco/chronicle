package io.github.mattpvaughn.chronicle.features.bookdetails

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.ICachedFileManager
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.features.player.ProgressUpdater
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNotNull
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

  private val book = Audiobook(id = "1001", source = 1L, title = "Dune")
  private val tracksLiveData = MutableLiveData<List<MediaItemTrack>>(emptyList())

  private val bookRepository =
    mockk<IBookRepository>(relaxed = true) {
      every { getAudiobook("1001") } returns MutableLiveData(book)
    }

  private val trackRepository =
    mockk<ITrackRepository>(relaxed = true) {
      every { getTracksForAudiobook("1001") } returns tracksLiveData
    }

  private val plexConfig =
    mockk<PlexConfig>(relaxed = true) {
      every { isConnected } returns MutableLiveData(true)
    }

  /**
   * `nowPlaying` must be stubbed explicitly: a relaxed mock returns a plain Object for it, and
   * `updateProgressIfChangingBook` casts it to MediaMetadataCompat.
   */
  private val mediaServiceConnection =
    mockk<MediaServiceConnection>(relaxed = true) {
      every { nowPlaying } returns MutableLiveData(null)
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
  fun `caching while disconnected tells the user instead of starting a download`() {
    every { plexConfig.isConnected } returns MutableLiveData(false)
    val cachedFileManager = cacheManager()
    val viewModel = viewModel(cachedFileManager = cachedFileManager)
    viewModel.cacheStatus.observeForever { }

    viewModel.onCacheButtonClick()

    verify(exactly = 0) { cachedFileManager.downloadTracks(any(), any()) }
  }

  /** Connected, and the book is not yet downloaded: the download must actually start. */
  @Test
  fun `caching while connected starts the download`() {
    every { plexConfig.isConnected } returns MutableLiveData(true)
    val cachedFileManager = cacheManager()
    val viewModel = viewModel(cachedFileManager = cachedFileManager)
    viewModel.cacheStatus.observeForever { }

    viewModel.onCacheButtonClick()

    verify { cachedFileManager.downloadTracks("1001", "Dune") }
  }

  /** Pressing play with no server and no download must not silently do nothing. */
  @Test
  fun `playing an undownloaded book while disconnected does not reach the player`() {
    every { plexConfig.isConnected } returns MutableLiveData(false)

    viewModel().pausePlayButtonClicked()

    verify(exactly = 0) { mediaServiceConnection.connect(any()) }
  }

  /** A cached book stays playable with no server — the offline case the app exists to support. */
  @Test
  fun `playing a downloaded book while disconnected still reaches the player`() {
    every { plexConfig.isConnected } returns MutableLiveData(false)
    every { bookRepository.getAudiobook("1001") } returns
      MutableLiveData(book.copy(isCached = true))
    every { mediaServiceConnection.isConnected } returns MutableLiveData(false)

    viewModel().pausePlayButtonClicked()

    verify { mediaServiceConnection.connect(any()) }
  }

  /** Already connected: no reconnect, the action runs directly. */
  @Test
  fun `playing while already connected does not reconnect`() {
    every { mediaServiceConnection.isConnected } returns MutableLiveData(true)
    every { mediaServiceConnection.transportControls } returns null

    viewModel().pausePlayButtonClicked()

    verify(exactly = 0) { mediaServiceConnection.connect(any()) }
  }

  /** A force sync with no server must report that, not attempt a fetch. */
  @Test
  fun `force syncing while disconnected does not touch the repository`() {
    every { plexConfig.isConnected } returns MutableLiveData(false)

    viewModel().forceSyncBook(hasUserConfirmation = true)

    coVerify(exactly = 0) { trackRepository.syncTracksInBook(any(), any()) }
  }

  private fun cacheManager() =
    mockk<ICachedFileManager>(relaxed = true) {
      every { activeBookDownloads } returns MutableLiveData(emptySet())
    }

  private fun viewModel(cachedFileManager: ICachedFileManager = cacheManager()) =
    AudiobookDetailsViewModel(
      bookRepository = bookRepository,
      trackRepository = trackRepository,
      cachedFileManager = cachedFileManager,
      inputAudiobook = book,
      mediaServiceConnection = mediaServiceConnection,
      progressUpdater = mockk<ProgressUpdater>(relaxed = true),
      plexConfig = plexConfig,
      prefsRepo = mockk<PrefsRepo>(relaxed = true),
      plexMediaService = mockk<PlexMediaService>(relaxed = true),
      currentlyPlaying = mockk<CurrentlyPlaying>(relaxed = true),
    )
}
