package io.github.mattpvaughn.chronicle.features.currentlyplaying

import android.content.SharedPreferences
import android.text.format.DateUtils
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import io.github.mattpvaughn.chronicle.data.model.EMPTY_CHAPTER
import io.github.mattpvaughn.chronicle.data.model.EMPTY_TRACK
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Construction and wiring for `CurrentlyPlayingViewModel`, which was at 0% instruction coverage.
 *
 * This exists because of a crash it would have caught. `currentChapter` was written as
 * `get() = activeChapter`, resolving to a property declared *below* it — and Kotlin initialises
 * properties in declaration order, so the alias read null during construction and MainActivity
 * died on launch with "Parameter specified as non-null is null" (cu-87). The source comment at the
 * declarations says as much: *"Nothing in the unit suite constructs this ViewModel, so only the app
 * caught it."*
 *
 * Merely constructing the class is therefore a real test: every `LiveData` and `StateFlow` in the
 * body is evaluated eagerly at construction, so a declaration-order mistake fails here.
 */
class CurrentlyPlayingViewModelTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  @get:Rule
  val mainDispatcherRule = MainDispatcherRule()

  /**
   * `DateUtils.formatElapsedTime` is a real Android static that the sleep-timer readout calls
   * during construction. Stubbed rather than designed around: formatting a duration for display is
   * exactly the framework's job, and moving it out to satisfy a test would be the tail wagging the
   * dog.
   */
  @Before
  fun stubAndroidFormatting() {
    mockkStatic(DateUtils::class)
    every { DateUtils.formatElapsedTime(any(), any()) } returns "0:00"
  }

  @After
  fun unstubAndroidFormatting() {
    unmockkStatic(DateUtils::class)
  }

  private val book = Audiobook(id = "1001", source = 1L, title = "Dune")

  private val currentlyPlaying =
    mockk<CurrentlyPlaying>(relaxed = true) {
      every { this@mockk.book } returns MutableStateFlow(EMPTY_AUDIOBOOK)
      every { track } returns MutableStateFlow(EMPTY_TRACK)
      every { chapter } returns MutableStateFlow(EMPTY_CHAPTER)
    }

  private val bookRepository =
    mockk<IBookRepository>(relaxed = true) {
      every { getAudiobook(any()) } returns MutableLiveData(book)
    }

  private val trackRepository =
    mockk<ITrackRepository>(relaxed = true) {
      every { getTracksForAudiobook(any()) } returns
        MutableLiveData(emptyList<MediaItemTrack>())
    }

  private val workManager =
    mockk<WorkManager>(relaxed = true) {
      every { getWorkInfosByTagLiveData(any()) } returns
        MutableLiveData(emptyList<WorkInfo>())
    }

  private val sharedPrefs =
    mockk<SharedPreferences>(relaxed = true) {
      every { getFloat(any(), any()) } returns 1.0f
      every { getBoolean(any(), any()) } returns false
      every { getInt(any(), any()) } returns 0
    }

  /** The regression itself: construction must not throw. */
  @Test
  fun `the view model can be constructed without a device`() {
    assertNotNull(viewModel())
  }

  /**
   * `currentChapter` and `activeChapter` must be the *same* LiveData.
   *
   * They used to differ — the timeline read a raw `currentlyPlaying.chapter` that only playback
   * callbacks refreshed, while the chapter list highlighted one derived from saved progress, so
   * the two disagreed until playback started (cu-87). That is the owner's *"chapter list
   * highlights the wrong chapter compared to the timeline position"*.
   */
  @Test
  fun `the timeline and the chapter list read the same chapter`() {
    val viewModel = viewModel()

    assertSame(
      "currentChapter must alias activeChapter, or the two readouts disagree",
      viewModel.activeChapter,
      viewModel.currentChapter,
    )
  }

  /** The alias must be non-null at construction, which is precisely what the crash was. */
  @Test
  fun `the current chapter is available immediately after construction`() {
    assertNotNull("a null here is the cu-87 launch crash", viewModel().currentChapter)
  }

  private fun viewModel() =
    CurrentlyPlayingViewModel(
      bookRepository = bookRepository,
      trackRepository = trackRepository,
      localBroadcastManager = mockk<LocalBroadcastManager>(relaxed = true),
      mediaServiceConnection = mockk<MediaServiceConnection>(relaxed = true),
      prefsRepo = mockk<PrefsRepo>(relaxed = true),
      plexConfig = mockk<PlexConfig>(relaxed = true),
      currentlyPlaying = currentlyPlaying,
      workManager = workManager,
      sharedPrefs = sharedPrefs,
    )
}
