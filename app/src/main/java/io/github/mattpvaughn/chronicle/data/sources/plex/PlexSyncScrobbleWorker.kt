package io.github.mattpvaughn.chronicle.data.sources.plex

import android.content.Context
import androidx.work.*
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import io.github.mattpvaughn.chronicle.data.sources.plex.model.getDuration
import timber.log.Timber

/**
 * Reports listening progress to Plex, retrying with backoff when the network is not
 * available.
 *
 * A thin shell: the decision about what to send and whether a failure is retryable
 * lives in [ProgressReporter], which is testable without a live application. This class
 * owns only the WorkManager contract.
 *
 * Extends [CoroutineWorker], matching the download workers. The previous version
 * extended the blocking [androidx.work.Worker] but did its work in a launched coroutine
 * and returned success without awaiting it, so every report was fire-and-forget on a
 * scope whose worker WorkManager already considered finished.
 */
class PlexSyncScrobbleWorker(
  context: Context,
  workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
  private val trackRepository = Injector.get().trackRepo()
  private val plexPrefs = Injector.get().plexPrefs()
  private val plexMediaService = Injector.get().plexMediaService()

  override suspend fun doWork(): Result {
    // Nothing can be reported without a token, and waiting will not produce one.
    // Re-auth is cu-10's job.
    val authToken = plexPrefs.user?.authToken ?: plexPrefs.accountAuthToken
    if (authToken.isEmpty()) {
      Timber.w("Progress report skipped: not logged in")
      return Result.failure()
    }

    val reporter =
      ProgressReporter(
        api = PlexProgressApi(plexMediaService),
        lookupTrack = { trackRepository.getTrackAsync(it) },
        lookupBookDuration = { bookId ->
          trackRepository.getTracksForAudiobookAsync(bookId).getDuration()
        },
      )

    val request =
      ProgressReporter.Request(
        trackId = inputData.requireInt(TRACK_ID_ARG),
        playbackState = inputData.requireString(TRACK_STATE_ARG),
        trackProgress = inputData.requireLong(TRACK_POSITION_ARG),
        bookProgress = inputData.requireLong(BOOK_PROGRESS),
      )

    return when (reporter.report(request)) {
      ProgressReporter.Outcome.SUCCESS -> Result.success()
      // Retry is what makes the backoff configured at the enqueue site mean anything.
      ProgressReporter.Outcome.RETRY -> Result.retry()
      ProgressReporter.Outcome.PERMANENT_FAILURE -> Result.failure()
    }
  }

  companion object {
    const val TRACK_ID_ARG = "Track ID"
    const val TRACK_STATE_ARG = "State"
    const val TRACK_POSITION_ARG = "Track position"
    const val BOOK_PROGRESS = "Book progress"

    fun makeWorkerData(
      trackId: Int,
      playbackState: String,
      trackProgress: Long,
      bookProgress: Long,
    ): Data {
      require(trackId != TRACK_NOT_FOUND)
      return workDataOf(
        TRACK_ID_ARG to trackId,
        TRACK_POSITION_ARG to trackProgress,
        TRACK_STATE_ARG to playbackState,
        BOOK_PROGRESS to bookProgress,
      )
    }
  }

  private fun Data.requireInt(key: String): Int {
    require(hasKeyWithValueOfType<Int>(key))
    return getInt(key, -1)
  }

  private fun Data.requireLong(key: String): Long {
    require(hasKeyWithValueOfType<Long>(key))
    return getLong(key, -1L)
  }

  private fun Data.requireString(key: String): String {
    require(hasKeyWithValueOfType<String>(key))
    return getString(key) ?: ""
  }
}
