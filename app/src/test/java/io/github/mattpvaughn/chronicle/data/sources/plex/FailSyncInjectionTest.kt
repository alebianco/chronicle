package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * The contract the `fail_sync` debug hook depends on (cu-73).
 *
 * `--ez fail_sync true` makes the "position not synced" badge reachable by substituting a
 * [ProgressApi] that throws. Which *kind* of throw matters, and the choice is not obvious:
 *
 * - a **4xx** -> [ProgressReporter.Outcome.PERMANENT_FAILURE] -> `Result.failure()` ->
 *   `hasFailedSync` is true -> the badge shows
 * - an **[java.io.IOException]** -> [ProgressReporter.Outcome.RETRY] -> `Result.retry()` ->
 *   WorkManager reports ENQUEUED, which `hasFailedSync` deliberately does **not** treat as a
 *   failure -> the badge stays hidden
 *
 * So injecting the intuitive "network error" would leave the badge invisible and look like the
 * badge itself was broken. This pins the distinction so a later edit to the hook cannot quietly
 * pick the wrong one, and so the reason is written down rather than rediscovered.
 *
 * Every other link in that chain is already covered — [ProgressReporterTest] for the outcome
 * mapping, `SyncFailureStateTest` for the WorkManager states. What was missing was a live-server
 * path to trigger it at all, which is what the hook adds.
 */
class FailSyncInjectionTest {
  private val track =
    MediaItemTrack(id = "1", parentKey = "10", title = "Track", duration = 60_000L)

  private fun reporter(api: ProgressApi) =
    ProgressReporter(
      api = api,
      lookupTrack = { track },
      lookupBookDuration = { 60_000L },
      lookupBookViewCount = { 1L },
    )

  private fun request() =
    ProgressReporter.Request(
      trackId = track.id,
      playbackState = "playing",
      trackProgress = 1_000L,
      bookProgress = 1_000L,
    )

  /** What `DebugHooks.wrapProgressApi` substitutes when `fail_sync` is set. */
  private class AlwaysFailingProgressApi : ProgressApi {
    override suspend fun reportProgress(
      ratingKey: String,
      offset: String,
      key: String,
      duration: Long,
      playState: String,
      playbackTime: Long,
      playQueueItemId: Long,
    ) {
      throw HttpException(
        Response.error<Unit>(
          400,
          "fail_sync debug hook".toResponseBody("text/plain".toMediaType()),
        ),
      )
    }

    override suspend fun markWatched(key: String) = Unit
  }

  @Test
  fun `the injected failure is terminal, so the badge can appear`() =
    runTest {
      val outcome = reporter(AlwaysFailingProgressApi()).report(request())

      assertEquals(
        "only PERMANENT_FAILURE reaches Result.failure(), which is the one state " +
          "hasFailedSync looks for",
        ProgressReporter.Outcome.PERMANENT_FAILURE,
        outcome,
      )
    }

  @Test
  fun `an IOException would NOT make the badge appear`() =
    runTest {
      // The mistake this test exists to prevent. A "simulate no network" injection is the
      // intuitive choice and is silently wrong for this purpose.
      val ioFailing =
        object : ProgressApi {
          override suspend fun reportProgress(
            ratingKey: String,
            offset: String,
            key: String,
            duration: Long,
            playState: String,
            playbackTime: Long,
            playQueueItemId: Long,
          ) = throw java.io.IOException("no network")

          override suspend fun markWatched(key: String) = Unit
        }

      assertEquals(
        "a transient failure must stay retryable — which is correct behaviour, and " +
          "therefore the wrong tool for reaching the badge",
        ProgressReporter.Outcome.RETRY,
        reporter(ioFailing).report(request()),
      )
    }

  @Test
  fun `a 5xx would also not be terminal`() =
    runTest {
      // Same trap one step along: a server error is retried, so it cannot drive the badge either.
      val serverErrorFailing =
        object : ProgressApi {
          override suspend fun reportProgress(
            ratingKey: String,
            offset: String,
            key: String,
            duration: Long,
            playState: String,
            playbackTime: Long,
            playQueueItemId: Long,
          ) {
            throw HttpException(
              Response.error<Unit>(503, "".toResponseBody("text/plain".toMediaType())),
            )
          }

          override suspend fun markWatched(key: String) = Unit
        }

      assertEquals(
        ProgressReporter.Outcome.RETRY,
        reporter(serverErrorFailing).report(request()),
      )
    }
}
