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
    // Unfinished by default: the one-shot guard must not suppress a first, legitimate scrobble.
    lookupBookViewCount = { 0L },
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

  /**
   * The duration sent to Plex is deliberately **doubled**.
   *
   * Plex marks an item finished at 90% of whatever duration it is told, so reporting the real
   * duration makes it auto-finish a book the listener has not finished — one of the owner's
   * reported symptoms (books completing early). The doubling is load-bearing, and nothing asserted
   * it: a mutant replacing the multiplication with a division survived, because every test here
   * only counted calls.
   */
  @Test
  fun `the duration reported to Plex is doubled`() =
    runTest {
      val api = FakeProgressApi()

      reporterWith(api).report(REQUEST)

      assertEquals(
        "Plex finishes at 90% of the duration it is told; doubling keeps it from finishing early",
        10_000L,
        api.lastReportedDuration,
      )
    }

  /** The position itself must be reported as-is, not scaled along with the duration. */
  @Test
  fun `the reported offset is the real track position`() =
    runTest {
      val api = FakeProgressApi()

      reporterWith(api).report(REQUEST.copy(trackProgress = 1_234L))

      assertEquals("1234", api.lastReportedOffset)
    }

  /**
   * The retry boundary: 500 and above is transient, 499 and below is a rejection.
   *
   * The existing cases use 503 and 401, both far from the edge, so a boundary mutant on
   * `code >= 500` survived.
   */
  @Test
  fun `a 500 is retried`() =
    runTest {
      val reporter = reporterWith(FakeProgressApi(failWith = httpException(500)))

      assertEquals(ProgressReporter.Outcome.RETRY, reporter.report(REQUEST))
    }

  @Test
  fun `a 499 is permanent`() =
    runTest {
      val reporter = reporterWith(FakeProgressApi(failWith = httpException(499)))

      assertEquals(
        "below 500 is a rejection a retry cannot fix",
        ProgressReporter.Outcome.PERMANENT_FAILURE,
        reporter.report(REQUEST),
      )
    }
}
