package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.data.model.Audiobook.Companion.NO_SERIES_INDEX
import io.github.mattpvaughn.chronicle.data.model.Audiobook.Companion.merge
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `narrator`, `series` and `seriesIndex` follow a **third** merge rule, and both arms must apply
 * it.
 *
 * The two rules already documented on `merge` are "always local" (`progress`, `playbackSpeed` —
 * the server knows nothing about them) and "always network". These three are neither: they come
 * from Plex's `Style`/`Mood` convention, which the **detail** endpoint carries and the library
 * listing does not (cu-24). So a refresh merges a network copy whose narrator is empty simply
 * because it was never fetched.
 *
 * - Preferring the network value blanks a narrator on every library refresh.
 * - Preferring the local value makes a re-tagged book permanently uncorrectable.
 *
 * Hence network-when-present, local-otherwise. `seriesIndex` expresses "absent" as
 * [NO_SERIES_INDEX] rather than emptiness, because it is an `Int`.
 *
 * **Both arms are exercised deliberately.** `merge` branches on `lastViewedAt` and only one side
 * runs for a given pair, so a rule applied to one arm and missed in the other looks correct in any
 * test that happens to take the fixed path — the exact trap `PerBookSpeedTest` was written to
 * catch for `playbackSpeed` (cu-20). The `newer`/`older` pairs below select the arms explicitly.
 */
class MergeSeriesFieldsTest {
  private fun book(
    lastViewedAt: Long,
    narrator: String = "",
    series: String = "",
    seriesIndex: Int = NO_SERIES_INDEX,
  ) = Audiobook(
    id = "1001",
    source = TEST_SOURCE,
    title = "Mistborn",
    lastViewedAt = lastViewedAt,
    narrator = narrator,
    series = series,
    seriesIndex = seriesIndex,
  )

  // `network.lastViewedAt > local.lastViewedAt` -> first arm.
  private fun mergeViaNewerNetwork(
    network: Audiobook.() -> Audiobook,
    local: Audiobook.() -> Audiobook,
  ) = merge(book(200L).network(), book(100L).local())

  // otherwise -> second arm.
  private fun mergeViaOlderNetwork(
    network: Audiobook.() -> Audiobook,
    local: Audiobook.() -> Audiobook,
  ) = merge(book(100L).network(), book(200L).local())

  @Test
  fun `a network narrator wins in both arms`() {
    assertEquals(
      "Kate Reading",
      mergeViaNewerNetwork(
        { copy(narrator = "Kate Reading") },
        { copy(narrator = "stale") },
      ).narrator,
    )
    assertEquals(
      "Kate Reading",
      mergeViaOlderNetwork(
        { copy(narrator = "Kate Reading") },
        { copy(narrator = "stale") },
      ).narrator,
    )
  }

  /**
   * The library-listing case, and the one that actually bites: the network copy has no narrator
   * because the listing endpoint does not carry `Style` tags at all. Taking it would blank a
   * correct local value on every refresh.
   */
  @Test
  fun `an empty network narrator keeps the local one in both arms`() {
    assertEquals(
      "Kate Reading",
      mergeViaNewerNetwork({ this }, { copy(narrator = "Kate Reading") }).narrator,
    )
    assertEquals(
      "Kate Reading",
      mergeViaOlderNetwork({ this }, { copy(narrator = "Kate Reading") }).narrator,
    )
  }

  @Test
  fun `an empty network series keeps the local one in both arms`() {
    assertEquals(
      "Mistborn",
      mergeViaNewerNetwork({ this }, { copy(series = "Mistborn") }).series,
    )
    assertEquals(
      "Mistborn",
      mergeViaOlderNetwork({ this }, { copy(series = "Mistborn") }).series,
    )
  }

  @Test
  fun `a network series index wins in both arms`() {
    assertEquals(
      200,
      mergeViaNewerNetwork({ copy(seriesIndex = 200) }, { copy(seriesIndex = 100) }).seriesIndex,
    )
    assertEquals(
      200,
      mergeViaOlderNetwork({ copy(seriesIndex = 200) }, { copy(seriesIndex = 100) }).seriesIndex,
    )
  }

  /**
   * `seriesIndex` signals absence with the sentinel, not with emptiness — so this is the branch a
   * copy-paste from the `ifEmpty` fields above would get wrong.
   */
  @Test
  fun `an absent network series index keeps the local one in both arms`() {
    assertEquals(
      150,
      mergeViaNewerNetwork({ this }, { copy(seriesIndex = 150) }).seriesIndex,
    )
    assertEquals(
      150,
      mergeViaOlderNetwork({ this }, { copy(seriesIndex = 150) }).seriesIndex,
    )
  }

  /**
   * Book 0 reads as unknown by design (cu-146), so a network `0` must not overwrite a real local
   * index — it is indistinguishable from "not tagged".
   */
  @Test
  fun `a network index of the sentinel value never overwrites a real local index`() {
    assertEquals(
      300,
      mergeViaNewerNetwork(
        { copy(seriesIndex = NO_SERIES_INDEX) },
        { copy(seriesIndex = 300) },
      ).seriesIndex,
    )
  }
}
