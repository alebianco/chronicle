package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.github.mattpvaughn.chronicle.data.sources.plex.model.ConnectionTier
import io.github.mattpvaughn.chronicle.data.sources.plex.model.tier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a Plex connection maps to a preference tier.
 *
 * `/api/v2/resources` reports `local` and `relay` per connection, and the app asks for
 * relay routes explicitly (`includeRelay = 1`) — but the model dropped `relay`, so relay
 * connections were raced on equal footing with LAN despite being capped around 2 Mbps
 * behind an extra hop through Plex's infrastructure (cu-11).
 */
class ConnectionTierTest {
  @Test
  fun `a local connection is LAN`() {
    assertEquals(
      ConnectionTier.LAN,
      Connection(uri = "https://10-0-0-1.h.plex.direct:32400", local = true).tier,
    )
  }

  @Test
  fun `a remote non-relay connection is DIRECT`() {
    assertEquals(
      ConnectionTier.DIRECT,
      Connection(uri = "https://x.h.plex.direct:32400", local = false).tier,
    )
  }

  @Test
  fun `a relay connection is RELAY`() {
    assertEquals(
      ConnectionTier.RELAY,
      Connection(uri = "https://x.h.plex.direct:443", local = false, relay = true).tier,
    )
  }

  @Test
  fun `relay outranks local when Plex reports both`() {
    assertEquals(
      "a relay hop is never the fast path, whatever else the connection claims",
      ConnectionTier.RELAY,
      Connection(uri = "https://x.h.plex.direct:443", local = true, relay = true).tier,
    )
  }

  @Test
  fun `tiers sort LAN then DIRECT then RELAY`() {
    val shuffled =
      listOf(ConnectionTier.RELAY, ConnectionTier.LAN, ConnectionTier.DIRECT).shuffled()

    assertEquals(
      "declaration order is the preference order; the chooser relies on it",
      listOf(ConnectionTier.LAN, ConnectionTier.DIRECT, ConnectionTier.RELAY),
      shuffled.sorted(),
    )
  }

  @Test
  fun `relay defaults to false so an existing fixture parses unchanged`() {
    assertEquals(
      "Moshi runs in reflection mode; a response without the field must not break",
      false,
      Connection(uri = "https://x", local = false).relay,
    )
  }
}
