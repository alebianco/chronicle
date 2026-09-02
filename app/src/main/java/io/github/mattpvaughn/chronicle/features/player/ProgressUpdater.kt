package io.github.mattpvaughn.chronicle.features.player

import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.work.*
import io.github.mattpvaughn.chronicle.data.local.IBookRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import io.github.mattpvaughn.chronicle.data.model.*
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack.Companion.EMPTY_TRACK
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexSyncScrobbleWorker
import io.github.mattpvaughn.chronicle.data.sources.plex.model.getDuration
import io.github.mattpvaughn.chronicle.features.currentlyplaying.CurrentlyPlaying
import io.github.mattpvaughn.chronicle.features.player.ProgressUpdater.Companion.BOOK_FINISHED_END_OFFSET_MILLIS
import io.github.mattpvaughn.chronicle.features.player.ProgressUpdater.Companion.NETWORK_CALL_FREQUENCY
import io.github.mattpvaughn.chronicle.features.player.ProgressUpdater.Companion.PROGRESS_SYNC_WORK_TAG
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

/**
 * Responsible for updating playback progress of the current book/track to the local DB and to the
 * server at regular intervals while a [MediaControllerCompat] indicates that playback is active
 */
interface ProgressUpdater {
  /** Begin regularly updating the local DB and remote servers while playback is active */
  fun startRegularProgressUpdates()

  /**
   * Updates local DBs to reflect the track/book progress passed in via [progress] for a track
   * with id [trackId] and a book containing that track.
   *
   * Updates book/track progress in remote DB if [forceNetworkUpdate] == true or every
   * [NETWORK_CALL_FREQUENCY] calls. Pass additional [playbackState] for [PlexSyncScrobbleWorker]
   * to pass playback state to server
   */
  fun updateProgress(
    trackId: String,
    playbackState: String,
    progress: Long,
    forceNetworkUpdate: Boolean,
  )

  /** Update progress without providing any parameters */
  fun updateProgressWithoutParameters()

  /**
   * Writes progress and returns only once the local write has completed.
   *
   * Exists for service teardown. [updateProgress] launches into the service scope, and
   * `MediaPlayerService.onDestroy` cancelled that scope on the line after asking for a
   * final update — the repository writes are `suspend` + `withContext`, so cancellation
   * landed on them and the last known position never reached the database. This variant
   * is bound to its caller instead, so the write survives the service dying.
   */
  suspend fun updateProgressBlocking(
    trackId: String,
    playbackState: String,
    progress: Long,
  )

  /** Cancels regular progress updates */
  fun cancel()

  companion object {
    val BOOK_FINISHED_END_OFFSET_MILLIS = 2.minutes.inWholeMilliseconds

    /**
     * The frequency which the remote server is updated at: once for every [NETWORK_CALL_FREQUENCY]
     * calls to the local database
     */
    const val NETWORK_CALL_FREQUENCY = 10

    /**
     * Tags every progress-sync work request, so sync health can be observed without
     * knowing which track ids are in flight (the unique work name is the track id).
     */
    const val PROGRESS_SYNC_WORK_TAG = "progress-sync"
  }
}

class SimpleProgressUpdater
  @Inject
  constructor(
    private val serviceScope: CoroutineScope,
    private val trackRepository: ITrackRepository,
    private val bookRepository: IBookRepository,
    private val workManager: WorkManager,
    private val prefsRepo: PrefsRepo,
    private val currentlyPlaying: CurrentlyPlaying,
    private val dispatchers: DispatcherProvider,
  ) : ProgressUpdater {
    var mediaController: MediaControllerCompat? = null

    /** Frequency of progress updates */
    private val updateProgressFrequencyMs = 1000L

    /** Tracks the number of times [updateLocalProgress] has been called this session */
    private var tickCounter = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressAction = { startRegularProgressUpdates() }

    /**
     * Updates the current track/audiobook progress in the local db and remote server.
     *
     * If we are within 2 minutes of the end of the book and playback stops, mark the book as
     * "finished" by updating the first track to progress=0 and setting it as the most recently viewed
     */
    override fun startRegularProgressUpdates() {
      requireNotNull(mediaController).let { controller ->
        if (controller.playbackState?.isPlaying != false) {
          val position = controller.playbackState?.currentPlayBackPosition
          // A position of 0 at the very start of playback is almost always "the player has not
          // seeked to the saved offset yet", not "the listener is at the beginning". This loop
          // starts the moment playback is requested, so the first tick used to report time=0 to
          // Plex — the owner watched a book at 70% flash 0% and jump back, and the zero *was*
          // written to the server. Had the app been closed in that window, 0 would have become the
          // saved position.
          //
          // Skipping the tick costs nothing: the next one is a second later and carries the real
          // offset. Genuinely starting a book at 0 is covered by the same next tick.
          if (position != null && position > 0L) {
            serviceScope.launch(context = serviceScope.coroutineContext + dispatchers.io) {
              updateProgress(
                controller.metadata?.id ?: TRACK_NOT_FOUND,
                MediaPlayerService.PLEX_STATE_PLAYING,
                position,
                false,
              )
            }
          } else {
            Timber.i("Skipping progress report at position 0: player has not seeked yet")
          }
        }
      }
      handler.postDelayed(updateProgressAction, updateProgressFrequencyMs)
    }

    override fun updateProgressWithoutParameters() {
      val controller = mediaController ?: return
      val playbackState =
        when (controller.playbackState.state) {
          PlaybackStateCompat.STATE_PLAYING -> MediaPlayerService.PLEX_STATE_PLAYING
          PlaybackStateCompat.STATE_PAUSED -> MediaPlayerService.PLEX_STATE_PAUSED
          PlaybackStateCompat.STATE_STOPPED -> MediaPlayerService.PLEX_STATE_PAUSED
          else -> ""
        }
      val currentTrack = controller.metadata.id ?: return
      val currentTrackProgress = controller.playbackState.currentPlayBackPosition
      updateProgress(
        currentTrack,
        playbackState,
        currentTrackProgress,
        false,
      )
    }

    override fun updateProgress(
      trackId: String,
      playbackState: String,
      progress: Long,
      forceNetworkUpdate: Boolean,
    ) {
      if (trackId == TRACK_NOT_FOUND) {
        return
      }
      serviceScope.launch(context = serviceScope.coroutineContext + dispatchers.io) {
        writeProgress(trackId, playbackState, progress, forceNetworkUpdate)
      }
    }

    override suspend fun updateProgressBlocking(
      trackId: String,
      playbackState: String,
      progress: Long,
    ) {
      if (trackId == TRACK_NOT_FOUND) {
        return
      }
      // withContext, not serviceScope.launch: the caller's lifetime governs this write,
      // so a dying service cannot cancel it out from under us. Always forces the network
      // report, since there will be no later tick to carry the position.
      withContext(dispatchers.io) {
        writeProgress(trackId, playbackState, progress, forceNetworkUpdate = true)
      }
    }

    /**
     * The shared body of both update paths. Suspends until the local write is done; the
     * network report is handed to WorkManager, which survives process death by design.
     */
    private suspend fun writeProgress(
      trackId: String,
      playbackState: String,
      progress: Long,
      forceNetworkUpdate: Boolean,
    ) {
      val currentTime = System.currentTimeMillis()

      // Cheapest guard first: this runs once a second, so a known-bad id must cost nothing.
      // It used to sit *after* the two reads below, which it exists to avoid.
      if (trackId == TRACK_NOT_FOUND) {
        return
      }

      // One read, not two. `getBookIdForTrack` fetches this very row and throws everything but
      // `parentKey` away, so asking for the track separately queried the same row twice per tick
      // (cu-110/cu-104).
      //
      // A missing row must still stop here. `getBookIdForTrack` signalled that by returning
      // `NO_AUDIOBOOK_FOUND_ID`, which is *not* what `EMPTY_TRACK.parentKey` holds ("-1"), so the
      // null check has to happen on the row itself rather than on the id it yields.
      val track: MediaItemTrack = trackRepository.getTrackAsync(trackId) ?: return
      val bookId: String = track.parentKey

      // No reason to update if the track or book doesn't exist in the DB
      if (bookId == NO_AUDIOBOOK_FOUND_ID || bookId == EMPTY_TRACK.parentKey) {
        return
      }

      val tracks = trackRepository.getTracksForAudiobookAsync(bookId)
      val book = bookRepository.getAudiobookAsync(bookId)
      val bookProgress = tracks.getTrackStartTime(track) + progress
      val bookDuration = tracks.getDuration()

      currentlyPlaying.update(
        book = book ?: EMPTY_AUDIOBOOK,
        track = tracks.getActiveTrack(),
        tracks = tracks,
      )

      // Update local DB
      if (!prefsRepo.debugOnlyDisableLocalProgressTracking) {
        updateLocalProgress(
          bookId = bookId,
          currentTime = currentTime,
          trackProgress = progress,
          trackId = trackId,
          bookProgress = bookProgress,
          tracks = tracks,
          bookDuration = bookDuration,
          playbackState = playbackState,
        )
      }

      // Update server once every [networkCallFrequency] calls, or when manual updates
      // have been specifically requested
      if (forceNetworkUpdate || tickCounter % NETWORK_CALL_FREQUENCY == 0L) {
        updateNetworkProgress(
          trackId,
          playbackState,
          progress,
          bookProgress,
        )
      }
    }

    private fun updateNetworkProgress(
      trackId: String,
      playbackState: String,
      trackProgress: Long,
      bookProgress: Long,
    ) {
      val syncWorkerConstraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
      val inputData =
        PlexSyncScrobbleWorker.makeWorkerData(
          trackId = trackId,
          playbackState = playbackState,
          trackProgress = trackProgress,
          bookProgress = bookProgress,
        )
      val worker =
        OneTimeWorkRequestBuilder<PlexSyncScrobbleWorker>()
          .setInputData(inputData)
          .setConstraints(syncWorkerConstraints)
          // A shared tag, because the unique work name is per-track: without it, showing
          // sync health means knowing which track ids to watch.
          .addTag(PROGRESS_SYNC_WORK_TAG)
          .setBackoffCriteria(
            BackoffPolicy.LINEAR,
            WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS,
            TimeUnit.MILLISECONDS,
          )
          .build()

      workManager
        .beginUniqueWork(trackId, ExistingWorkPolicy.REPLACE, worker)
        .enqueue()
    }

    private suspend fun updateLocalProgress(
      bookId: String,
      currentTime: Long,
      trackProgress: Long,
      trackId: String,
      bookProgress: Long,
      tracks: List<MediaItemTrack>,
      bookDuration: Long,
      playbackState: String,
    ) {
      tickCounter++
      bookRepository.updateProgress(bookId, currentTime, trackProgress)
      trackRepository.updateTrackProgress(trackProgress, trackId, currentTime)
      bookRepository.updateTrackData(
        bookId,
        bookProgress,
        tracks.getDuration(),
        tracks.size,
      )

      // Being near the end is not the same as being finished. This check used to run on
      // every tick, so listening through the last two minutes marked the book complete
      // while it was still playing — and setWatched resets progress, which presents as
      // the book jumping back to the start (#67). PlexSyncScrobbleWorker already gated
      // the same rule on playback having ended; the local path did not.
      val hasUserEndedPlayback =
        playbackState == MediaPlayerService.PLEX_STATE_PAUSED ||
          playbackState == MediaPlayerService.PLEX_STATE_STOPPED
      // A duration of 0 means the tracks are not loaded, not that the book is over: the window
      // check would be trivially true and mark an unstarted book finished. Same guard as
      // `Audiobook.isCompleted()` and the server-side path in `ProgressReporter`.
      if (
        hasUserEndedPlayback &&
        bookDuration > 0L &&
        bookDuration - bookProgress <= BOOK_FINISHED_END_OFFSET_MILLIS
      ) {
        Timber.i("Marking $bookId as finished")
        // `setWatched` propagates a server failure since cu-98. Caught here rather than left to
        // escape: this runs on the progress-reporting path, and failing to mark a finished book
        // must not take down the reporting that keeps the listener's position.
        runCatching { bookRepository.setWatched(bookId) }
          .onFailure { Timber.e(it, "Failed to mark $bookId as finished") }
      }
    }

    override fun cancel() {
      handler.removeCallbacks(updateProgressAction)
    }
  }
