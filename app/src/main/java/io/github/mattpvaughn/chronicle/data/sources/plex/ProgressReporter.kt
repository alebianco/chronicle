package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.data.model.NO_AUDIOBOOK_FOUND_ID
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_PAUSED
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_STOPPED
import io.github.mattpvaughn.chronicle.features.player.ProgressUpdater.Companion.BOOK_FINISHED_END_OFFSET_MILLIS
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException

/**
 * Reports listening progress to Plex and decides whether a failure is worth retrying.
 *
 * Split out of [PlexSyncScrobbleWorker] so the decision is testable: the worker resolves
 * its collaborators through `Injector.get()` in field initialisers, so constructing one
 * needs a live `ChronicleApplication` and the whole Dagger graph. The worker now owns
 * only the WorkManager plumbing; the judgement lives here.
 *
 * The API surface is narrowed to [ProgressApi] rather than taking `PlexMediaService`,
 * so a test does not have to stand up Retrofit to exercise a retry.
 */
class ProgressReporter(
  private val api: ProgressApi,
  private val lookupTrack: suspend (Int) -> MediaItemTrack?,
  private val lookupBookDuration: suspend (Int) -> Long,
) {
  /** What the caller should tell WorkManager. */
  enum class Outcome {
    SUCCESS,

    /** Transient: try again with backoff. */
    RETRY,

    /** Retrying would fail identically forever. */
    PERMANENT_FAILURE,
  }

  data class Request(
    val trackId: Int,
    val playbackState: String,
    val trackProgress: Long,
    val bookProgress: Long,
  )

  suspend fun report(request: Request): Outcome {
    val track = lookupTrack(request.trackId)
    if (track == null || request.trackId == TRACK_NOT_FOUND) {
      // Nothing to report against. A retry would look up the same missing track.
      Timber.w("Progress report skipped: unknown track ${request.trackId}")
      return Outcome.PERMANENT_FAILURE
    }
    val bookId = track.parentKey
    if (bookId == NO_AUDIOBOOK_FOUND_ID) {
      Timber.w("Progress report skipped: track ${request.trackId} has no parent book")
      return Outcome.PERMANENT_FAILURE
    }

    return try {
      api.reportProgress(
        ratingKey = track.id.toString(),
        offset = request.trackProgress.toString(),
        key = "${MediaItemTrack.PARENT_KEY_PREFIX}${track.id}",
        // Plex marks an item finished at 90% of whatever duration it is told, so
        // doubling the real duration stops it auto-finishing a book the listener has
        // not finished. Removing this makes Plex mark books complete early.
        duration = track.duration * 2,
        playState = request.playbackState,
        playbackTime = request.trackProgress,
        playQueueItemId = track.playQueueItemID,
      )
      Timber.i("Reported progress for track ${track.id} at ${request.trackProgress}ms")
      markFinishedIfNeeded(track, bookId, request)
      Outcome.SUCCESS
    } catch (e: IOException) {
      // No connectivity, timeout, server unreachable. This is the airplane-mode case
      // cu-9 exists to fix: the position must survive until the network returns.
      Timber.w(e, "Progress report failed transiently; will retry")
      Outcome.RETRY
    } catch (e: HttpException) {
      if (e.code() >= HTTP_SERVER_ERROR) {
        Timber.w(e, "Progress report got ${e.code()}; will retry")
        Outcome.RETRY
      } else {
        // A 4xx is a rejection, not a blip — most likely an expired token, which a
        // retry cannot fix (cu-10 owns re-auth).
        Timber.e(e, "Progress report rejected with ${e.code()}; giving up")
        Outcome.PERMANENT_FAILURE
      }
    }
  }

  /**
   * Marks a track or book watched when playback has *ended* near the end of it.
   *
   * Failures here are logged and swallowed on purpose: the position report already
   * succeeded, and losing it because a "watched" flag did not stick would trade the
   * thing this task protects for cosmetics.
   */
  private suspend fun markFinishedIfNeeded(
    track: MediaItemTrack,
    bookId: Int,
    request: Request,
  ) {
    val hasUserEndedPlayback =
      request.playbackState == PLEX_STATE_STOPPED || request.playbackState == PLEX_STATE_PAUSED

    if (request.trackProgress > track.duration - TRACK_FINISHED_WINDOW_MILLIS) {
      runCatching { api.markWatched(track.id.toString()) }
        .onFailure { Timber.e(it, "Failed to mark track ${track.id} watched") }
    }

    if (!hasUserEndedPlayback) return

    val bookDuration = lookupBookDuration(bookId)
    if (bookDuration - request.bookProgress < BOOK_FINISHED_END_OFFSET_MILLIS) {
      runCatching { api.markWatched(bookId.toString()) }
        .onFailure { Timber.e(it, "Failed to mark book $bookId watched") }
    }
  }

  private companion object {
    const val HTTP_SERVER_ERROR = 500

    /** A track counts as finished within a second of its end. */
    const val TRACK_FINISHED_WINDOW_MILLIS = 1_000L
  }
}

/**
 * The slice of the Plex API progress reporting needs.
 *
 * Exists so [ProgressReporter] can be tested without Retrofit; the production
 * implementation delegates to `PlexMediaService`.
 */
interface ProgressApi {
  suspend fun reportProgress(
    ratingKey: String,
    offset: String,
    key: String,
    duration: Long,
    playState: String,
    playbackTime: Long,
    playQueueItemId: Long,
  )

  suspend fun markWatched(key: String)
}

/** Production [ProgressApi], backed by the real Plex endpoints. */
class PlexProgressApi(
  private val plexMediaService: PlexMediaService,
) : ProgressApi {
  override suspend fun reportProgress(
    ratingKey: String,
    offset: String,
    key: String,
    duration: Long,
    playState: String,
    playbackTime: Long,
    playQueueItemId: Long,
  ) = plexMediaService.progress(
    ratingKey = ratingKey,
    offset = offset,
    key = key,
    duration = duration,
    playState = playState,
    hasMde = 1,
    playbackTime = playbackTime,
    playQueueItemId = playQueueItemId,
  )

  override suspend fun markWatched(key: String) = plexMediaService.watched(key)
}
