package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.testing.OTHER_TEST_SOURCE
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test

/**
 * `MediaItemTrack.source` survives a merge, in **both** arms (cu-127, cu-20's rule).
 *
 * A parsed network track carries [SourceId.UNKNOWN] — a response does not say which server it
 * came from. So an arm of [MediaItemTrack.merge] that omits `source` blanks the scope of every
 * track on every refresh, leaving a library that shows its books and none of their tracks.
 *
 * Both arms are exercised because only one runs for a given pair: a fix applied to one and missed
 * in the other looks correct in a test that happens to take the fixed path. That is exactly how
 * cu-20 found the `playbackSpeed` bug, and `PerBookSpeedTest` was written the same way.
 */
class TrackSourceScopeTest {
  private fun local(
    lastViewedAt: Long,
    source: SourceId = TEST_SOURCE,
  ) = MediaItemTrack(id = "2001", parentKey = "1001", source = source, lastViewedAt = lastViewedAt, progress = 4242L)

  /** A parsed response never knows its server, so this is what the network side really looks like. */
  private fun network(lastViewedAt: Long) =
    MediaItemTrack(id = "2001", parentKey = "1001", source = SourceId.UNKNOWN, lastViewedAt = lastViewedAt)

  @Test
  fun `the network-wins arm keeps the local scope`() {
    val merged = MediaItemTrack.merge(network = network(lastViewedAt = 200L), local = local(lastViewedAt = 100L))

    assertThat("the newer network copy must not blank the scope", merged.source, equalTo(TEST_SOURCE))
  }

  @Test
  fun `the local-wins arm keeps the local scope`() {
    val merged = MediaItemTrack.merge(network = network(lastViewedAt = 100L), local = local(lastViewedAt = 200L))

    assertThat(merged.source, equalTo(TEST_SOURCE))
  }

  /** `forceUseNetwork` takes the first arm explicitly, which is the one a sync path uses. */
  @Test
  fun `a forced network merge keeps the local scope`() {
    val merged =
      MediaItemTrack.merge(
        network = network(lastViewedAt = 100L),
        local = local(lastViewedAt = 200L),
        forceUseNetwork = true,
      )

    assertThat(merged.source, equalTo(TEST_SOURCE))
  }

  /**
   * A track already belonging to another server keeps that scope rather than adopting the one
   * doing the merging. The refresh that owns it will update it; this one must not steal it.
   */
  @Test
  fun `a track from another source keeps its own scope`() {
    val merged =
      MediaItemTrack.merge(
        network = network(lastViewedAt = 200L),
        local = local(lastViewedAt = 100L, source = OTHER_TEST_SOURCE),
      )

    assertThat(merged.source, equalTo(OTHER_TEST_SOURCE))
  }

  /** The merge must still do its real job — progress is the value a mistake here would cost. */
  @Test
  fun `scoping does not disturb the merge's own rules`() {
    val merged = MediaItemTrack.merge(network = network(lastViewedAt = 100L), local = local(lastViewedAt = 200L))

    assertThat("the local position must survive", merged.progress, equalTo(4242L))
  }
}
