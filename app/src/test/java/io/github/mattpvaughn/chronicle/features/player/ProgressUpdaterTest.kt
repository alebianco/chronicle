package io.github.mattpvaughn.chronicle.features.player

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.PlaybackStateCompat
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
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
        Audiobook(id = BOOK_ID, source = TEST_SOURCE, title = "Book")
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

  /**
   * Progress is stored **per track**, and since cu-165 the session's `PlaybackState.position` is
   * *chapter*-relative so Auto's scrubber matches the chapter title beside it. Reading the session
   * here would therefore save a chapter offset as a track offset — silently, and worse the further
   * into a book the listener is.
   *
   * The session is seeded with a deliberately different value so a regression to reading it fails
   * on the number rather than passing by coincidence.
   */
  @Test
  fun `the saved position comes from the track, not the chapter-relative session`() =
    runTest {
      val dispatchers = TestDispatcherProvider(testScheduler)
      val serviceScope = CoroutineScope(SupervisorJob() + dispatchers.io)
      val updater = updater(serviceScope, dispatchers)
      updater.trackPosition = { TRACK_FRAME_POSITION }
      updater.mediaController =
        mockk(relaxed = true) {
          every { playbackState } returns
            PlaybackStateCompat.Builder()
              .setState(PlaybackStateCompat.STATE_PLAYING, CHAPTER_RELATIVE_POSITION, 1f)
              .build()
          every { metadata } returns
            MediaMetadataCompat.Builder()
              .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, TRACK_ID)
              .build()
        }

      updater.startRegularProgressUpdates()
      advanceUntilIdle()

      coVerify { trackRepo.updateTrackProgress(TRACK_FRAME_POSITION, TRACK_ID, any()) }
    }

  /**
   * Without a track-position source the session remains the fallback, so the updater keeps working
   * before the service attaches one.
   */
  @Test
  fun `with no track position source the session is still used`() =
    runTest {
      val dispatchers = TestDispatcherProvider(testScheduler)
      val serviceScope = CoroutineScope(SupervisorJob() + dispatchers.io)
      val updater = updater(serviceScope, dispatchers)
      updater.mediaController =
        mockk(relaxed = true) {
          every { playbackState } returns
            PlaybackStateCompat.Builder()
              .setState(PlaybackStateCompat.STATE_PLAYING, CHAPTER_RELATIVE_POSITION, 1f)
              .build()
          every { metadata } returns
            MediaMetadataCompat.Builder()
              .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, TRACK_ID)
              .build()
        }

      updater.startRegularProgressUpdates()
      advanceUntilIdle()

      // A range, not the exact value: `currentPlayBackPosition` extrapolates from
      // `elapsedRealtime` while PLAYING, and the tick only runs at all while playing. The point is
      // that it is the *session's* number and not the track source's, which is far away.
      coVerify {
        trackRepo.updateTrackProgress(
          match { it >= CHAPTER_RELATIVE_POSITION && it < TRACK_FRAME_POSITION },
          TRACK_ID,
          any(),
        )
      }
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

  /**
   * The cu-168 handover: when a cast session takes over, the position must keep being saved from
   * whichever player is now current.
   *
   * The service supplies `trackPosition` as `{ currentPlayer?.currentPosition }` — a *read* of the
   * mutable field, not a captured player — so a swap is followed automatically. A supplier that
   * captured the local ExoPlayer instead would keep reporting a frozen position for the rest of the
   * cast, writing a stale offset once a second, and nothing would surface the fault. Mutating the
   * source here is what a `switchToPlayer` does from the updater's point of view.
   */
  @Test
  fun `the saved position follows a player swap, as a cast handover performs`() =
    runTest {
      val dispatchers = TestDispatcherProvider(testScheduler)
      val serviceScope = CoroutineScope(SupervisorJob() + dispatchers.io)
      val updater = updater(serviceScope, dispatchers)
      var activePlayerPosition = TRACK_FRAME_POSITION
      updater.trackPosition = { activePlayerPosition }
      updater.mediaController =
        mockk(relaxed = true) {
          every { playbackState } returns
            PlaybackStateCompat.Builder()
              .setState(PlaybackStateCompat.STATE_PLAYING, CHAPTER_RELATIVE_POSITION, 1f)
              .build()
          every { metadata } returns
            MediaMetadataCompat.Builder()
              .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, TRACK_ID)
              .build()
        }

      updater.startRegularProgressUpdates()
      advanceUntilIdle()
      coVerify { trackRepo.updateTrackProgress(TRACK_FRAME_POSITION, TRACK_ID, any()) }

      // The cast receiver takes over and reports its own, further-along position.
      activePlayerPosition = CAST_FRAME_POSITION
      updater.startRegularProgressUpdates()
      advanceUntilIdle()

      coVerify { trackRepo.updateTrackProgress(CAST_FRAME_POSITION, TRACK_ID, any()) }
    }

  private companion object {
    const val TRACK_ID = "3001"

    /** Deep into a book: the frames differ by a lot, which is where the bug would show. */
    const val TRACK_FRAME_POSITION = 4_500_000L

    /** What Auto's scrubber sees for the same instant — an offset inside the current chapter. */
    const val CHAPTER_RELATIVE_POSITION = 120_000L

    /** A position only the cast receiver would report, so a frozen supplier fails on the number. */
    const val CAST_FRAME_POSITION = 5_200_000L
    const val BOOK_ID = "1001"
  }
}
