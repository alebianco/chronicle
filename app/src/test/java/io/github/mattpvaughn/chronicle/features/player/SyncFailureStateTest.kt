package io.github.mattpvaughn.chronicle.features.player

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * Maps progress-sync work states to "is the user's position at risk?".
 *
 * Deliberately failure-only. The obvious three-state indicator (synced / pending /
 * failed) cannot be built honestly from this data:
 *
 * - Reports are enqueued under a unique name per track with
 *   [androidx.work.ExistingWorkPolicy.REPLACE], so a newer position *cancels* a pending
 *   older one. That is normal operation, but it surfaces as `CANCELLED` — indistinguishable
 *   from a real cancellation without tracking every id.
 * - `ENQUEUED` is the steady state between ticks during ordinary playback, so a "pending"
 *   badge would blink every ten seconds and teach the user to ignore it.
 *
 * So the only state worth showing is the one that means something: a report that has
 * genuinely run out of retries. Everything else is silence.
 */
class SyncFailureStateTest {
  @Test
  fun `a terminal failure is at risk`() {
    assertEquals(true, hasFailedSync(listOf(workInfo(WorkInfo.State.FAILED))))
  }

  @Test
  fun `a cancelled report is not a failure`() {
    assertEquals(
      "REPLACE cancels a pending report whenever a newer position arrives; that is " +
        "ordinary operation, not a sync problem",
      false,
      hasFailedSync(listOf(workInfo(WorkInfo.State.CANCELLED))),
    )
  }

  @Test
  fun `a retrying report is not yet a failure`() {
    assertEquals(
      "backoff is the mechanism working, not failing",
      false,
      hasFailedSync(listOf(workInfo(WorkInfo.State.ENQUEUED))),
    )
  }

  @Test
  fun `running and succeeded are not failures`() {
    assertEquals(false, hasFailedSync(listOf(workInfo(WorkInfo.State.RUNNING))))
    assertEquals(false, hasFailedSync(listOf(workInfo(WorkInfo.State.SUCCEEDED))))
  }

  @Test
  fun `one failure among many is still at risk`() {
    val infos =
      listOf(
        workInfo(WorkInfo.State.SUCCEEDED),
        workInfo(WorkInfo.State.FAILED),
        workInfo(WorkInfo.State.ENQUEUED),
      )

    assertEquals(true, hasFailedSync(infos))
  }

  @Test
  fun `no work at all is not a failure`() {
    assertEquals(
      "nothing has been reported yet, which is not the same as a report failing",
      false,
      hasFailedSync(emptyList()),
    )
  }

  private fun workInfo(state: WorkInfo.State) =
    WorkInfo(
      id = UUID.randomUUID(),
      state = state,
      tags = setOf(ProgressUpdater.PROGRESS_SYNC_WORK_TAG),
    )
}
