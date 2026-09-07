package io.github.mattpvaughn.chronicle.features.player

import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import io.github.mattpvaughn.chronicle.util.testExceptionHandler
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A pause must write the position it paused at.
 *
 * `ProgressUpdater`'s per-second tick is gated on `isPlaying`, so pausing stops it: without an
 * explicit flush the saved position is whatever the previous tick happened to capture, and no
 * PAUSED state ever reaches Plex. The book-switch path has `flushOutgoingBookProgress` and
 * a seek publishes its own position, but an ordinary pause — from the lock screen, the
 * notification or a headset button — had nothing. It is the defect behind
 * advplyr/audiobookshelf-app#1847 ("an hour of listening lost") and PaulWoitaschek/Voice#3351.
 *
 * The **state and position must come from the player, not the session**: `MediaSessionCompat`'s
 * playback state lags a frame behind, which is precisely why the seek path stopped using
 * `updateProgressWithoutParameters` on the seek path. A flush that read the session would report
 * the pre-pause position as still PLAYING — worse than not flushing, because it would overwrite a
 * good position with a stale one and tell the server playback continues.
 */
@RunWith(RobolectricTestRunner::class)
class PauseFlushesProgressTest {
  private val trackId = "2001"
  private val pausedAt = 123_456L

  private data class Report(
    val trackId: String,
    val state: String,
    val progress: Long,
    val forceNetwork: Boolean,
  )

  @Test
  fun `pausing writes the paused position and state`() {
    val reports = mutableListOf<Report>()

    callback(reports).onPause()

    assertEquals(
      "a pause must flush exactly once; the per-second tick has already stopped",
      1,
      reports.size,
    )
    assertEquals(
      Report(trackId, MediaPlayerService.PLEX_STATE_PAUSED, pausedAt, forceNetwork = true),
      reports.single(),
    )
  }

  /**
   * Reported as PAUSED, never PLAYING. Other devices resume from what the server was last told, so
   * a pause reported as PLAYING leaves the book looking in-progress on every other client.
   */
  @Test
  fun `the flush reports PAUSED rather than the stale session state`() {
    val reports = mutableListOf<Report>()

    callback(reports).onPause()

    assertEquals(MediaPlayerService.PLEX_STATE_PAUSED, reports.single().state)
  }

  /**
   * The position is read from the player, which is correct synchronously. The session is seeded
   * with a deliberately different, stale position so that a regression to reading session state
   * shows up as a wrong number rather than passing by coincidence.
   */
  @Test
  fun `the flushed position comes from the player, not the lagging session state`() {
    val reports = mutableListOf<Report>()

    callback(reports).onPause()

    assertEquals(
      "reading the session would report the pre-pause position",
      pausedAt,
      reports.single().progress,
    )
  }

  private fun callback(reports: MutableList<Report>): AudiobookMediaSessionCallback {
    val session =
      MediaSessionCompat(ApplicationProvider.getApplicationContext(), "PauseFlushesProgressTest")

    // A *stale* session position, distinct from the player's. Reading this instead of the player
    // is the regression these tests exist to catch.
    session.setPlaybackState(
      PlaybackStateCompat.Builder()
        .setState(PlaybackStateCompat.STATE_PLAYING, 99_000L, 1f)
        .build(),
    )

    val progressUpdater =
      mockk<ProgressUpdater>(relaxed = true) {
        every { updateProgress(any(), any(), any(), any()) } answers
          {
            reports +=
              Report(
                trackId = firstArg(),
                state = secondArg(),
                progress = thirdArg(),
                forceNetwork = arg(3),
              )
          }
      }

    return AudiobookMediaSessionCallback(
      plexPrefsRepo = mockk(relaxed = true),
      prefsRepo = mockk(relaxed = true),
      plexConfig = mockk(relaxed = true),
      // `isPrepared` must be true or `onPause` takes the resume-from-dead branch instead.
      mediaController =
        mockk(relaxed = true) {
          every { playbackState } returns
            PlaybackStateCompat.Builder()
              .setState(PlaybackStateCompat.STATE_PLAYING, 99_000L, 1f)
              .build()
          every { metadata } returns
            android.support.v4.media.MediaMetadataCompat.Builder()
              .putString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_MEDIA_ID,
                trackId,
              )
              .build()
        },
      dataSourceFactory = mockk(relaxed = true),
      trackRepository = mockk(relaxed = true),
      bookRepository = mockk(relaxed = true),
      serviceScope = CoroutineScope(Dispatchers.Unconfined),
      trackListStateManager = TrackListStateManager(),
      foregroundServiceController = mockk(relaxed = true),
      serviceController = mockk(relaxed = true),
      mediaSession = session,
      appContext = ApplicationProvider.getApplicationContext(),
      currentlyPlaying =
        mockk(relaxed = true) {
          every { track } returns MutableStateFlow(MediaItemTrack.EMPTY_TRACK)
        },
      progressUpdater = progressUpdater,
      defaultPlayer =
        mockk<ExoPlayer>(relaxed = true) {
          every { currentPosition } returns pausedAt
        },
      dispatchers = TestDispatcherProvider(),
      exceptionHandler = testExceptionHandler(),
      playbackSession = mockk(relaxed = true),
      playbackErrorBus = PlaybackErrorBus(),
    )
  }
}
