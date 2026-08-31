package io.github.mattpvaughn.chronicle.features.player

import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the two local-side faults in the position-loss family (#88/#112/#68/#67).
 *
 * Both are about a write that should happen and does not, or one that happens when it
 * should not — neither is visible from a passing build, which is why this file exists.
 *
 * Runs under Robolectric because [SimpleProgressUpdater] builds a
 * `Handler(Looper.getMainLooper())` in a field initialiser, so it cannot be constructed
 * on a bare JVM at all — the same reason `RoomMigrationTest` needs it.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressUpdaterTest {
  private val track =
    MediaItemTrack(id = TRACK_ID, parentKey = BOOK_ID, title = "Track 1", duration = 5_000L)

  private val tracks = listOf(track)

  private val trackRepo =
    mockk<ITrackRepository>(relaxed = true) {
      coEvery { getBookIdForTrack(TRACK_ID) } returns BOOK_ID
      coEvery { getTrackAsync(TRACK_ID) } returns track
      coEvery { getTracksForAudiobookAsync(BOOK_ID) } returns tracks
    }

  private val bookRepo =
    mockk<IBookRepository>(relaxed = true) {
      coEvery { getAudiobookAsync(BOOK_ID) } returns
        Audiobook(id = BOOK_ID, source = PLEX_SOURCE, title = "Book")
    }

  private val prefsRepo =
    mockk<PrefsRepo>(relaxed = true) {
      every { debugOnlyDisableLocalProgressTracking } returns false
    }

  /**
   * The swipe-away case. `MediaPlayerService.onDestroy` asked for a final progress
   * update and then cancelled `serviceJob` on the very next line; the update launches
   * into that scope and the repository writes are `suspend` + `withContext`, so
   * cancellation landed on them and the last known position never reached the database.
   */
  @Test
  fun `the final save completes even when the service scope is cancelled`() =
    runTest {
      val dispatchers = TestDispatcherProvider(testScheduler)
      val serviceJob = SupervisorJob()
      val serviceScope = CoroutineScope(serviceJob + dispatchers.io)
      val updater = updater(serviceScope, dispatchers)

      val save =
        launch {
          updater.updateProgressBlocking(TRACK_ID, MediaPlayerService.PLEX_STATE_STOPPED, 4_242L)
        }
      // Tear the service down while the save is in flight, exactly as onDestroy did.
      serviceJob.cancel()
      save.join()

      coVerify(exactly = 1) {
        trackRepo.updateTrackProgress(4_242L, TRACK_ID, any())
      }
    }

  /**
   * The fire-and-forget path must still work — this guards against "fixing" the above
   * by making every update block the caller.
   */
  @Test
  fun `a routine update still writes progress`() =
    runTest {
      val dispatchers = TestDispatcherProvider(testScheduler)
      val serviceScope = CoroutineScope(SupervisorJob() + dispatchers.io)
      val updater = updater(serviceScope, dispatchers)

      updater.updateProgress(TRACK_ID, MediaPlayerService.PLEX_STATE_PLAYING, 1_500L, false)
      advanceUntilIdle()

      coVerify { trackRepo.updateTrackProgress(1_500L, TRACK_ID, any()) }
    }

  /**
   * `updateLocalProgress` marked a book watched whenever it was within two minutes of
   * the end — on *every tick*, including mid-playback. `setWatched` resets progress, so
   * simply listening through the last two minutes sent the book back to the start (#67).
   */
  @Test
  fun `playing through the final minutes does not mark a book finished`() =
    runTest {
      val dispatchers = TestDispatcherProvider(testScheduler)
      val updater = updater(CoroutineScope(SupervisorJob() + dispatchers.io), dispatchers)

      // 4.5s into a 5s book: inside the two-minute window, but still playing.
      updater.updateProgress(TRACK_ID, MediaPlayerService.PLEX_STATE_PLAYING, 4_500L, false)
      advanceUntilIdle()

      coVerify(exactly = 0) { bookRepo.setWatched(BOOK_ID) }
    }

  @Test
  fun `pausing near the end does mark a book finished`() =
    runTest {
      val dispatchers = TestDispatcherProvider(testScheduler)
      val updater = updater(CoroutineScope(SupervisorJob() + dispatchers.io), dispatchers)

      updater.updateProgress(TRACK_ID, MediaPlayerService.PLEX_STATE_PAUSED, 4_500L, false)
      advanceUntilIdle()

      coVerify(exactly = 1) { bookRepo.setWatched(BOOK_ID) }
    }

  private fun updater(
    serviceScope: CoroutineScope,
    dispatchers: TestDispatcherProvider,
  ) = SimpleProgressUpdater(
    serviceScope = serviceScope,
    trackRepository = trackRepo,
    bookRepository = bookRepo,
    workManager = mockk(relaxed = true),
    prefsRepo = prefsRepo,
    currentlyPlaying = mockk<CurrentlyPlaying>(relaxed = true),
    dispatchers = dispatchers,
  )

  private companion object {
    const val TRACK_ID = 3001
    const val BOOK_ID = 1001
    const val PLEX_SOURCE = 1L
  }
}
