package io.github.mattpvaughn.chronicle.features.download

import com.tonyodev.fetch2.Download
import com.tonyodev.fetch2.Status
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What resumes after an interrupted download, and — more importantly — what does not.
 *
 * Before cu-76 the retry limit was 1, so a Wi-Fi blip mid-download ended a book for good and
 * nothing ever re-enqueued it. Raising the limit only helps while the app is running; a download
 * that already exhausted its retries sits at [Status.FAILED], and Fetch2 will not touch it again
 * without an explicit `retry`. `resumeAll()` does not cover those, which is the gap this closes.
 */
class ResumePlanTest {
  private fun download(
    id: Int,
    status: Status,
  ): Download =
    mockk {
      every { this@mockk.id } returns id
      every { this@mockk.status } returns status
    }

  @Test
  fun `a failed download is retried`() {
    val ids = ResumePlan.idsToRetry(listOf(download(1, Status.FAILED)))

    assertEquals(listOf(1), ids)
  }

  /**
   * The regression that matters: a download stranded by the old single-retry limit must be
   * picked up on next launch, not left failed forever.
   */
  @Test
  fun `several failed downloads are all retried`() {
    val ids =
      ResumePlan.idsToRetry(
        listOf(
          download(1, Status.FAILED),
          download(2, Status.FAILED),
          download(3, Status.FAILED),
        ),
      )

    assertEquals(listOf(1, 2, 3), ids)
  }

  /**
   * A cancel is a user decision. Resuming it would override that — the user would find a
   * download they deliberately stopped running again on next launch.
   */
  @Test
  fun `a cancelled download is left alone`() {
    val ids = ResumePlan.idsToRetry(listOf(download(1, Status.CANCELLED)))

    assertTrue("cancelling must survive a restart", ids.isEmpty())
  }

  @Test
  fun `a completed download is not retried`() {
    val ids = ResumePlan.idsToRetry(listOf(download(1, Status.COMPLETED)))

    assertTrue("retrying a finished download would reset the file", ids.isEmpty())
  }

  /** `resumeAll()` already handles these; naming them again would be redundant work. */
  @Test
  fun `a paused download is left to resumeAll`() {
    val ids = ResumePlan.idsToRetry(listOf(download(1, Status.PAUSED)))

    assertTrue(ids.isEmpty())
  }

  @Test
  fun `an in-flight download is not disturbed`() {
    val ids =
      ResumePlan.idsToRetry(
        listOf(
          download(1, Status.DOWNLOADING),
          download(2, Status.QUEUED),
        ),
      )

    assertTrue("retrying a running download would restart it", ids.isEmpty())
  }

  /** The mixed case is the realistic one: pick out only what actually needs a retry. */
  @Test
  fun `only the failed downloads are selected from a mixed set`() {
    val ids =
      ResumePlan.idsToRetry(
        listOf(
          download(1, Status.COMPLETED),
          download(2, Status.FAILED),
          download(3, Status.DOWNLOADING),
          download(4, Status.CANCELLED),
          download(5, Status.FAILED),
          download(6, Status.PAUSED),
        ),
      )

    assertEquals(listOf(2, 5), ids)
  }

  @Test
  fun `an empty download list yields nothing to retry`() {
    assertTrue(ResumePlan.idsToRetry(emptyList()).isEmpty())
  }
}
