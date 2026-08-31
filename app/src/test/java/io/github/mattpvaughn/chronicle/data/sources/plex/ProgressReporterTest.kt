package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.features.player.MediaPlayerService.Companion.PLEX_STATE_PAUSED
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * The retry contract for progress reporting.
 *
 * This is cu-9's first acceptance criterion — a position lost to airplane mode must be
 * recovered — and it was not met: the worker never returned `retry`, so the backoff
 * configured at the enqueue site did nothing and a failed report was dropped for good.
 *
 * These cases live on [ProgressReporter] rather than the worker because the worker
 * resolves its collaborators through `Injector.get()` at field-initialisation time,
 * which needs a real `ChronicleApplication` (and with it the whole Dagger graph, Fetch
 * and OkHttp) merely to construct. Extracting the decision into a plain class is what
 * makes it testable at all; the worker keeps only the WorkManager plumbing.
 */
class ProgressReporterTest {
  private val track =
    MediaItemTrack(
      id = "3001",
      parentKey = "1001",
      title = "Track 1",
      duration = 5_000L,
      index = 1,
    )

  @Test
  fun `a successful report is a success`() =
    runTest {
      val reporter = reporterWith(FakeProgressApi(failWith = null))

      val outcome = reporter.report(REQUEST)

      assertEquals(ProgressReporter.Outcome.SUCCESS, outcome)
    }

  @Test
  fun `no connectivity asks for a retry`() =
    runTest {
      val reporter = reporterWith(FakeProgressApi(failWith = IOException("no route to host")))

      val outcome = reporter.report(REQUEST)

      assertEquals(
        "a position lost to a network blip must be retried, not dropped",
        ProgressReporter.Outcome.RETRY,
        outcome,
      )
    }

  @Test
  fun `a server error asks for a retry`() =
    runTest {
      val reporter = reporterWith(FakeProgressApi(failWith = httpException(503)))

      val outcome = reporter.report(REQUEST)

      assertEquals(ProgressReporter.Outcome.RETRY, outcome)
    }

  @Test
  fun `a client error is permanent`() =
    runTest {
      val reporter = reporterWith(FakeProgressApi(failWith = httpException(401)))

      val outcome = reporter.report(REQUEST)

      assertEquals(
        "a 401 will fail identically forever; retrying only burns battery",
        ProgressReporter.Outcome.PERMANENT_FAILURE,
        outcome,
      )
    }

  @Test
  fun `an unknown track is permanent, not a retry`() =
    runTest {
      val reporter = reporterWith(FakeProgressApi(failWith = null), track = null)

      val outcome = reporter.report(REQUEST)

      assertEquals(
        "there is nothing to report against, so retrying cannot help",
        ProgressReporter.Outcome.PERMANENT_FAILURE,
        outcome,
      )
    }

  /**
   * A failure to mark the book *watched* must not discard a successful *position*
   * report — losing the position is the harm this task exists to prevent.
   */
  @Test
  fun `a failure marking watched still reports the position as synced`() =
    runTest {
      val api = FakeProgressApi(failWith = null, failWatchedWith = IOException("flaky"))
      val reporter = reporterWith(api, trackProgressAtEnd = true)

      val outcome = reporter.report(REQUEST.copy(trackProgress = 4_999L))

      assertEquals(ProgressReporter.Outcome.SUCCESS, outcome)
      assertEquals("the position must still have been sent", 1, api.progressCalls)
    }

  private fun reporterWith(
    api: FakeProgressApi,
    track: MediaItemTrack? = this.track,
    trackProgressAtEnd: Boolean = false,
  ) = ProgressReporter(
    api = api,
    lookupTrack = { track },
    lookupBookDuration = { if (trackProgressAtEnd) 5_000L else 60_000L },
  )

  private fun httpException(code: Int) =
    HttpException(
      Response.error<Unit>(code, "".toResponseBody("text/plain".toMediaType())),
    )

  private companion object {
    val REQUEST =
      ProgressReporter.Request(
        trackId = "3001",
        playbackState = PLEX_STATE_PAUSED,
        trackProgress = 2_000L,
        bookProgress = 2_000L,
      )
  }
}
