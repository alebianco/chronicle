package io.github.mattpvaughn.chronicle.features.currentlyplaying

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateUtils
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.IBookmarkRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.data.model.EMPTY_AUDIOBOOK
import io.github.mattpvaughn.chronicle.data.model.EMPTY_CHAPTER
import io.github.mattpvaughn.chronicle.data.model.EMPTY_TRACK
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexConfig
import io.github.mattpvaughn.chronicle.features.player.MediaServiceConnection
import io.github.mattpvaughn.chronicle.testing.MultiTrackBook
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import io.github.mattpvaughn.chronicle.util.MainDispatcherRule
import io.github.mattpvaughn.chronicle.util.settledValue
import io.github.mattpvaughn.chronicle.util.settledValues
import io.github.mattpvaughn.chronicle.util.testExceptionHandler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

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

  private val book = Audiobook(id = "1001", source = TEST_SOURCE, title = "Dune")

  private val currentlyPlaying =
    mockk<CurrentlyPlaying>(relaxed = true) {
      every { this@mockk.book } returns MutableStateFlow(EMPTY_AUDIOBOOK)
      every { track } returns MutableStateFlow(EMPTY_TRACK)
      every { chapter } returns MutableStateFlow(EMPTY_CHAPTER)
      every { bookPosition } returns MutableStateFlow(BookOffset.ZERO)
    }

  /**
   * The same collaborator, positioned mid-book on a **multi-track** book.
   *
   * `MID_BOOK_POSITION` (750_000) is 2m30s into track 2 and inside chapter 3, so the in-track
   * offset (150_000) and the book offset differ by a whole track. That difference is the point:
   * on a single-track book the two frames are the same number and a mix-up is invisible.
   */
  private fun midBookCurrentlyPlaying(): CurrentlyPlaying {
    val tracks = MultiTrackBook.midBookTracks()
    val activeTrack = tracks.single { it.id == MultiTrackBook.MID_TRACK_ID }
    val chapterThree = MultiTrackBook.chapters().single { it.id == MultiTrackBook.MID_CHAPTER_ID }
    return mockk(relaxed = true) {
      every { this@mockk.book } returns MutableStateFlow(MultiTrackBook.book())
      every { track } returns MutableStateFlow(activeTrack)
      every { chapter } returns MutableStateFlow(chapterThree)
      every { bookPosition } returns MutableStateFlow(MultiTrackBook.MID_BOOK_OFFSET)
    }
  }

  private val bookRepository =
    mockk<IBookRepository>(relaxed = true) {
      every { getAudiobook(any()) } returns MutableStateFlow(book)
    }

  private val trackRepository =
    mockk<ITrackRepository>(relaxed = true) {
      every { getTracksForAudiobook(any()) } returns
        MutableStateFlow(emptyList<MediaItemTrack>())
    }

  private val bookmarkRepository = mockk<IBookmarkRepository>(relaxed = true)

  private val workManager =
    mockk<WorkManager>(relaxed = true) {
      every { getWorkInfosByTagFlow(any()) } returns
        MutableStateFlow(emptyList<WorkInfo>())
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

  /**
   * Reads a `StateFlow` after letting it actually run.
   *
   * Two things are needed and each fails silently on its own. A `stateIn(WhileSubscribed)` does
   * not collect its upstream until something subscribes, so `.value` is the seed until then. And
   * `MainDispatcherRule` installs a `StandardTestDispatcher`, which *queues* work rather than
   * running it — so the `combine` never produces a value until the scheduler is advanced.
   *
   * Getting either wrong yields the seed, which is indistinguishable from "the arithmetic is
   * broken" unless you look. A local alias for [settledValue], which does both.
   */
  private fun <T> TestScope.observedValue(flow: StateFlow<T>): T = settledValue(flow)

  /**
   * Chapter-relative progress, on a multi-track book (cu-115 / cu-73 fourth sweep).
   *
   * This was `track.progress - chapter.bookStartTimeOffset` — an in-track offset minus a
   * book-absolute one. At this position that is `150_000 - 600_000 = -450_000`. It reached
   * `DateUtils.formatElapsedTime` and the chapter slider, and it was correct only on a
   * single-track book where the two frames coincide.
   *
   * **There was no test at any track count**, and the `coerceAtLeast(0L)` guard added with the fix
   * means a future regression presents as a stuck `0:00` rather than a negative number — invisible
   * to a human watching the screen as well as to the suite. Hence this test.
   */
  @Test
  fun `chapter progress is measured from the chapter start in the book frame`() =
    runTest(mainDispatcherRule.testDispatcher) {
      val viewModel = viewModel(midBookCurrentlyPlaying())

      assertEquals(
        "750000 into the book, in a chapter starting at 600000, is 150000 into the chapter",
        150_000L,
        observedValue(viewModel.chapterProgress),
      )
    }

  /** The slider reads the same value, or the thumb and the label disagree. */
  @Test
  fun `the chapter slider agrees with the chapter progress readout`() =
    runTest(mainDispatcherRule.testDispatcher) {
      val viewModel = viewModel(midBookCurrentlyPlaying())

      // Both subscribed together: see `settledValues` for why doing them one at a time makes
      // whichever is second read its `stateIn` seed.
      val (readout, slider) =
        settledValues(viewModel.chapterProgress, viewModel.chapterProgressForSlider)

      assertEquals(readout, slider)
    }

  /**
   * The slider must catch up once the drag guard opens (cu-198).
   *
   * `isSliding` was a plain `var` read inside `.filter { !isSliding }` on the two slider flows. A
   * predicate reading a mutable field **outside** the stream is not part of that stream's state,
   * so flipping it re-emits nothing: whatever arrived while the guard was closed is dropped for
   * good, and the thumb only recovers when the upstream next ticks. Paused, it never does.
   *
   * The same defect blocks the Compose migration outright — Compose renders `state.value` and has
   * no "write time" at which to consult a field — which is why this landed before the screen moved.
   *
   * **The test has to move the position while the guard is closed.** Asserting only that the value
   * is right afterwards passes against the broken code too, since the last *pre-drag* emission is
   * already correct. What separates the two is a position that changed during the drag: the
   * observable guard republishes it on release, the `var` never does.
   */
  @Test
  fun `the slider catches up on a position that moved during the drag`() =
    runTest(mainDispatcherRule.testDispatcher) {
      val position = MutableStateFlow(MultiTrackBook.MID_BOOK_OFFSET)
      val tracks = MultiTrackBook.midBookTracks()
      val activeTrack = tracks.single { it.id == MultiTrackBook.MID_TRACK_ID }
      val chapterThree = MultiTrackBook.chapters().single { it.id == MultiTrackBook.MID_CHAPTER_ID }
      val currentlyPlaying =
        mockk<CurrentlyPlaying>(relaxed = true) {
          every { this@mockk.book } returns MutableStateFlow(MultiTrackBook.book())
          every { track } returns MutableStateFlow(activeTrack)
          every { chapter } returns MutableStateFlow(chapterThree)
          every { bookPosition } returns position
        }
      val viewModel = viewModel(currentlyPlaying)

      // `UnconfinedTestDispatcher` so each emission is collected as it is produced. On the
      // rule's `StandardTestDispatcher` the collectors are merely *queued*, and every list reads
      // empty — which looks exactly like the bug under test.
      val slider = mutableListOf<Long>()
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        viewModel.chapterProgressForSlider.collect { slider.add(it) }
      }
      val readout = mutableListOf<Long>()
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        viewModel.chapterProgress.collect { readout.add(it) }
      }
      advanceUntilIdle()

      viewModel.onSlideStart()
      // Playback moves on while the user is dragging. The guard must swallow this...
      position.value = BookOffset(MultiTrackBook.MID_BOOK_OFFSET.millis + 30_000L)
      advanceUntilIdle()
      assertEquals("the drag must not be overwritten mid-gesture", 150_000L, slider.last())

      viewModel.onSlideFinished()
      advanceUntilIdle()

      // ...and then publish it, with no further tick from the player.
      assertEquals(
        "on release the slider must catch up to where playback actually is",
        readout.last(),
        slider.last(),
      )
      assertEquals("and that is the moved position, not the pre-drag one", 180_000L, slider.last())
    }

  /**
   * Never negative, whatever the frames do. This is the property `coerceAtLeast(0L)` guarantees;
   * asserted directly so the guard cannot be dropped silently.
   */
  @Test
  fun `chapter progress is never negative`() =
    runTest(mainDispatcherRule.testDispatcher) {
      val activeTrack =
        MultiTrackBook.midBookTracks().single { it.id == MultiTrackBook.MID_TRACK_ID }
      // A chapter that starts *after* the reported position: the frames disagree, which is the
      // condition that used to produce a large negative.
      val laterChapter = MultiTrackBook.chapters().last()
      val positioned =
        mockk<CurrentlyPlaying>(relaxed = true) {
          every { this@mockk.book } returns MutableStateFlow(MultiTrackBook.book())
          every { track } returns MutableStateFlow(activeTrack)
          every { chapter } returns MutableStateFlow(laterChapter)
          every { bookPosition } returns MutableStateFlow(MultiTrackBook.MID_BOOK_OFFSET)
        }

      val progress = observedValue(viewModel(positioned).chapterProgress)

      assertNotNull("the flow must have produced a value", progress)
      assertTrue("a negative chapter progress reaches the slider and the clock", progress!! >= 0L)
    }

  /**
   * A bookmark records the **book** offset, not the in-track one (cu-22).
   *
   * Asserted on the multi-track fixture where the two differ by a whole track: on a single-track
   * book they are the same number, so a frame mix-up here would be invisible — which is the exact
   * shape of the six bugs cu-136 made into a type error.
   */
  @Test
  fun `adding a bookmark records the book offset`() =
    runTest {
      val added = slot<BookOffset>()
      coEvery {
        bookmarkRepository.add(any(), capture(added), any(), any())
      } answers {
        Bookmark(bookId = MultiTrackBook.BOOK_ID, position = added.captured)
      }

      viewModel(midBookCurrentlyPlaying()).addBookmark()
      advanceUntilIdle()

      assertEquals(
        "a bookmark points into the book; the in-track offset is a different number here",
        MultiTrackBook.MID_BOOK_OFFSET,
        added.captured,
      )
    }

  @Test
  fun `adding a bookmark records the book it belongs to`() =
    runTest {
      val bookId = slot<String>()
      coEvery {
        bookmarkRepository.add(capture(bookId), any(), any(), any())
      } answers {
        Bookmark(bookId = bookId.captured, position = BookOffset.ZERO)
      }

      viewModel(midBookCurrentlyPlaying()).addBookmark()
      advanceUntilIdle()

      assertEquals(MultiTrackBook.BOOK_ID, bookId.captured)
    }

  /** Nothing playing means nowhere to attach a bookmark, so the write must not be attempted. */
  @Test
  fun `adding a bookmark with no book playing does nothing`() =
    runTest {
      viewModel().addBookmark()
      advanceUntilIdle()

      coVerify(exactly = 0) { bookmarkRepository.add(any(), any(), any(), any()) }
    }

  /**
   * A repository failure is reported, not swallowed: the user pressed a button and is entitled to
   * know it did nothing.
   */
  @Test
  fun `a failed bookmark write tells the user`() =
    runTest {
      coEvery { bookmarkRepository.add(any(), any(), any(), any()) } throws IOException("disk")

      val vm = viewModel(midBookCurrentlyPlaying())
      vm.addBookmark()
      advanceUntilIdle()

      assertNotNull(
        "a silent failure leaves the user believing a bookmark exists",
        vm.showUserMessage.value,
      )
    }

  /** A note is trimmed on the way in, so a stray newline does not become part of it. */
  @Test
  fun `saving a note trims it`() =
    runTest {
      val note = slot<String>()
      coEvery { bookmarkRepository.updateNote(any(), capture(note)) } returns Unit

      viewModel().setBookmarkNote("bm-1", "  the riddle game\n")
      advanceUntilIdle()

      assertEquals("the riddle game", note.captured)
    }

  @Test
  fun `deleting a bookmark reaches the repository`() =
    runTest {
      viewModel().deleteBookmark("bm-1")
      advanceUntilIdle()

      coVerify { bookmarkRepository.delete("bm-1") }
    }

  private fun viewModel(playing: CurrentlyPlaying = currentlyPlaying) =
    CurrentlyPlayingViewModel(
      bookRepository = bookRepository,
      trackRepository = trackRepository,
      localBroadcastManager = mockk<LocalBroadcastManager>(relaxed = true),
      mediaServiceConnection = mockk<MediaServiceConnection>(relaxed = true),
      prefsRepo = mockk<PrefsRepo>(relaxed = true),
      plexConfig = mockk<PlexConfig>(relaxed = true),
      currentlyPlaying = playing,
      workManager = workManager,
      bookmarkRepository = bookmarkRepository,
      sharedPrefs = sharedPrefs,
      exceptionHandler = testExceptionHandler(),
      appContext = mockk<Context>(relaxed = true),
    )
}
