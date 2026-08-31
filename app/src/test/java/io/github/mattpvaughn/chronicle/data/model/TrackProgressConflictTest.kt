package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-track conflict rule: the side with the newer `lastViewedAt` wins its `viewOffset`.
 *
 * This is where decision-16 says all position conflicts are resolved — one rule, one place. It is
 * also the re-listen guard the ADR flags as the sharpest edge: a user who deliberately seeks back to
 * an earlier chapter writes a *newer* `lastViewedAt` locally, so the local offset wins and the next
 * sync does not drag them forward again.
 *
 * The comparison only became meaningful in cu-14, which fixed the units: Plex reports `lastViewedAt`
 * in **seconds** while the local DB writes millis, so the network value was ~1000× smaller and could
 * never win. A second device's position was silently discarded on every refresh. These tests use
 * millis on both sides, as `plexTimestampToMillis` now guarantees.
 */
class TrackProgressConflictTest {
  private fun track(
    progress: Long,
    lastViewedAt: Long,
    cached: Boolean = false,
  ) = MediaItemTrack(
    id = "2001",
    parentKey = "1001",
    title = "Track 1",
    duration = 5_000L,
    progress = progress,
    lastViewedAt = lastViewedAt,
    cached = cached,
  )

  /** A second device listened more recently: adopt its position. */
  @Test
  fun `a newer network position wins`() {
    val merged =
      MediaItemTrack.merge(
        network = track(progress = 4_000L, lastViewedAt = 2_000L),
        local = track(progress = 1_000L, lastViewedAt = 1_000L),
      )

    assertEquals(4_000L, merged.progress)
  }

  /** This device listened more recently: keep the local position. */
  @Test
  fun `a newer local position wins`() {
    val merged =
      MediaItemTrack.merge(
        network = track(progress = 4_000L, lastViewedAt = 1_000L),
        local = track(progress = 1_000L, lastViewedAt = 2_000L),
      )

    assertEquals(1_000L, merged.progress)
    assertEquals("the newer timestamp must survive too", 2_000L, merged.lastViewedAt)
  }

  /**
   * The re-listen case from decision-16. A deliberate seek *backwards* is newer, so it must stick —
   * the user is not dragged forward to where the server thinks they were.
   */
  @Test
  fun `a deliberate seek backwards is not undone by a sync`() {
    val afterSeekingBack = track(progress = 500L, lastViewedAt = 9_000L)
    val serverThinksFurtherAhead = track(progress = 4_800L, lastViewedAt = 3_000L)

    val merged =
      MediaItemTrack.merge(network = serverThinksFurtherAhead, local = afterSeekingBack)

    assertEquals(
      "re-listening to an earlier part must not be reverted by the next refresh",
      500L,
      merged.progress,
    )
  }

  /** Equal timestamps: no new information, so do not move the user. */
  @Test
  fun `an equal timestamp keeps the local position`() {
    val merged =
      MediaItemTrack.merge(
        network = track(progress = 4_000L, lastViewedAt = 1_000L),
        local = track(progress = 1_000L, lastViewedAt = 1_000L),
      )

    assertEquals(1_000L, merged.progress)
  }

  /** forceUseNetwork is the explicit override — a user-requested force-sync. */
  @Test
  fun `forcing the network adopts its position regardless of timestamps`() {
    val merged =
      MediaItemTrack.merge(
        network = track(progress = 4_000L, lastViewedAt = 1_000L),
        local = track(progress = 1_000L, lastViewedAt = 9_000L),
        forceUseNetwork = true,
      )

    assertEquals(4_000L, merged.progress)
  }

  /** Cached status is local-only and must survive either branch — the server has no such concept. */
  @Test
  fun `cached status is always kept from the local copy`() {
    val newerNetwork =
      MediaItemTrack.merge(
        network = track(progress = 4_000L, lastViewedAt = 2_000L),
        local = track(progress = 1_000L, lastViewedAt = 1_000L, cached = true),
      )
    val newerLocal =
      MediaItemTrack.merge(
        network = track(progress = 4_000L, lastViewedAt = 1_000L),
        local = track(progress = 1_000L, lastViewedAt = 2_000L, cached = true),
      )

    assertEquals(true, newerNetwork.cached)
    assertEquals(true, newerLocal.cached)
  }
}
