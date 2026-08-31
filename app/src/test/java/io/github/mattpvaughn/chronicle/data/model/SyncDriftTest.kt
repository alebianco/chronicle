package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Last-write-wins between a local position and the server's.
 *
 * Both `MediaItemTrack.merge` and `Audiobook.merge` decide with
 * `network.lastViewedAt > local.lastViewedAt`, which only works if the two values share a
 * unit. They did not: Plex reports Unix **seconds** (`1600000200` in the recorded fixture,
 * i.e. 2020-09-13) while `ProgressUpdater` writes `System.currentTimeMillis()` —
 * **milliseconds**, around 1.79e12 today.
 *
 * So the network value was ~1000x smaller than any local one and could never win. A position
 * set on a second device was silently discarded on every refresh, which is the drift cu-14
 * exists to resolve.
 */
class SyncDriftTest {
  @Test
  fun `plex seconds are normalised to millis`() {
    assertEquals(1_600_000_200_000L, plexTimestampToMillis(1_600_000_200L))
  }

  @Test
  fun `a value already in millis is left alone`() {
    // Defensive: if Plex ever reports millis, doubling the conversion would push the
    // timestamp ~50,000 years into the future and make the server always win.
    val alreadyMillis = 1_788_127_200_000L

    assertEquals(alreadyMillis, plexTimestampToMillis(alreadyMillis))
  }

  @Test
  fun `zero stays zero`() {
    assertEquals(
      "never viewed must not become 1970-in-millis, which would still be a real ordering",
      0L,
      plexTimestampToMillis(0L),
    )
  }

  @Test
  fun `a newer server position now wins the merge`() {
    val localViewedAt = 1_788_000_000_000L
    val serverViewedAt = plexTimestampToMillis(1_788_100_000L) // later, in seconds

    assertTrue(
      "this comparison is what adopts a second device's position",
      serverViewedAt > localViewedAt,
    )
  }

  @Test
  fun `a newer local position still wins`() {
    val localViewedAt = 1_788_200_000_000L
    val serverViewedAt = plexTimestampToMillis(1_788_100_000L)

    assertTrue(
      "listening on this device must not be overwritten by a stale server value",
      localViewedAt > serverViewedAt,
    )
  }

  @Test
  fun `the un-normalised comparison was always wrong`() {
    val rawPlexSeconds = 1_788_100_000L
    val localMillis = 1_600_000_000_000L // an *older* local position, from 2020

    assertTrue(
      "raw seconds lose to millis even when the server is years newer — the bug",
      rawPlexSeconds < localMillis,
    )
  }
}
