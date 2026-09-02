package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A second device must adopt a newer position from the server.
 *
 * Reproduces the exact numbers measured on two real devices during the cu-73 live pass: the tablet
 * played *Ender's Game* track `151445` to 244973 ms and reported it; the phone opened the book,
 * received `"viewOffset":236955,"lastViewedAt":1788384988` in `/children`, and **kept its own
 * 189 ms**. The round trip is the whole point of having a server (cu-14, decision-16).
 */
class PositionAdoptionTest {
  private fun track(
    id: String = "151445",
    progress: Long,
    lastViewedAt: Long,
  ) = MediaItemTrack(
    id = id,
    parentKey = "151444",
    title = "Ender's Game - Chapter 01",
    index = 1,
    progress = progress,
    lastViewedAt = lastViewedAt,
  )

  /** The live failure, in the units the two sides actually use. */
  @Test
  fun `a newer network position wins over an older local one`() {
    // Server: seconds, as Plex sends them. Local: millis, as the app stores them.
    val network = track(progress = 236955, lastViewedAt = plexTimestampToMillis(1788384988))
    val local = track(progress = 189, lastViewedAt = 1788384769136)

    val merged = MediaItemTrack.merge(network = network, local = local)

    assertEquals(
      "device B must adopt A's position; keeping 189 ms is the cu-73 failure",
      236955,
      merged.progress,
    )
  }

  /** The converse, which a naive fix would break: a *stale* server value must not win. */
  @Test
  fun `an older network position does not clobber a newer local one`() {
    val network = track(progress = 1000, lastViewedAt = plexTimestampToMillis(1788384000))
    val local = track(progress = 244973, lastViewedAt = 1788384997729)

    val merged = MediaItemTrack.merge(network = network, local = local)

    assertEquals(
      "local listening in progress must not be pulled backwards by a stale server value",
      244973,
      merged.progress,
    )
  }

  /**
   * The units trap. Plex sends `lastViewedAt` in **seconds**; the app stores millis. Comparing
   * them raw makes the local value ~1000x larger, so the network never wins — which is what this
   * test would catch if the conversion were dropped at the call site.
   */
  @Test
  fun `seconds from the server compare correctly against stored millis`() {
    val serverSeconds = 1788384988L
    val storedMillis = 1788384769136L

    val converted = plexTimestampToMillis(serverSeconds)

    assertEquals(1788384988000L, converted)
    assertEquals(
      "a raw comparison would make the network look older and silently lose",
      true,
      converted > storedMillis,
    )
  }

  @Test
  fun `forceUseNetwork adopts the network position regardless of timestamps`() {
    // The explicit-refresh path: the user asked, so the server is authoritative.
    val network = track(progress = 500, lastViewedAt = plexTimestampToMillis(1))
    val local = track(progress = 244973, lastViewedAt = 1788384997729)

    val merged = MediaItemTrack.merge(network = network, local = local, forceUseNetwork = true)

    assertEquals(500, merged.progress)
  }

  @Test
  fun `the cached flag is always kept from the local copy`() {
    // A downloaded file is a local fact the server knows nothing about; losing it would make a
    // cached book stream again (cu-83 territory).
    val network = track(progress = 236955, lastViewedAt = plexTimestampToMillis(1788384988)).copy(cached = false)
    val local = track(progress = 189, lastViewedAt = 1788384769136).copy(cached = true)

    val merged = MediaItemTrack.merge(network = network, local = local)

    assertEquals(true, merged.cached)
  }
}
