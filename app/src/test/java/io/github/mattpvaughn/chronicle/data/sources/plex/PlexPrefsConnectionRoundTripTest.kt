package io.github.mattpvaughn.chronicle.data.sources.plex

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.github.mattpvaughn.chronicle.data.sources.plex.model.ConnectionTier
import io.github.mattpvaughn.chronicle.data.sources.plex.model.tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The `SharedPreferences` round trip for a chosen server (cu-107).
 *
 * The real implementation had **no test at all** — every other test injects the in-memory
 * `FakePlexPrefsRepo` — which is why cu-11's tiering could be silently inert from the second
 * launch onwards while all of its own unit tests passed. Those tests construct [Connection]
 * objects directly and assert the chooser's decisions; nothing crossed the persistence boundary
 * where the `local` and `relay` flags were being dropped.
 *
 * So these tests deliberately exercise the *real* class against a real `SharedPreferences`, and
 * the property they protect is the one that failed on a device: a connection must come back with
 * the tier it went in with.
 */
@RunWith(RobolectricTestRunner::class)
class PlexPrefsConnectionRoundTripTest {
  private lateinit var prefs: SharedPreferences
  private lateinit var repo: SharedPreferencesPlexPrefsRepo

  /** Modelled on a real `/api/v2/resources` response, captured in cu-107. */
  private val lan =
    Connection(
      uri = "https://192-168-1-54.hash.plex.direct:32400",
      local = true,
      relay = false,
      protocol = "https",
    )
  private val wan =
    Connection(
      uri = "https://87-17-202-231.hash.plex.direct:32400",
      local = false,
      relay = false,
      protocol = "https",
    )
  private val relay =
    Connection(
      uri = "https://relay.plex.direct:443",
      local = false,
      relay = true,
      protocol = "https",
    )

  private fun serverWith(connections: List<Connection>) =
    ServerModel(
      name = "Test Server",
      connections = connections,
      serverId = "server-id",
      accessToken = "server-token",
      owned = true,
    )

  @Before
  fun setUp() {
    val context: Context = ApplicationProvider.getApplicationContext()
    prefs = context.getSharedPreferences("PlexPrefsConnectionRoundTripTest", Context.MODE_PRIVATE)
    prefs.edit().clear().commit()
    repo = SharedPreferencesPlexPrefsRepo(prefs, Moshi.Builder().build())
  }

  @Test
  fun `a stored connection keeps its tier`() {
    repo.server = serverWith(listOf(lan, wan, relay))

    val restored = repo.server
    assertNotNull(restored)

    // The failure cu-107 records: every connection came back DIRECT, because it was rebuilt
    // from a bare URI string with local and relay at their false defaults.
    assertEquals(
      listOf(ConnectionTier.LAN, ConnectionTier.DIRECT, ConnectionTier.RELAY),
      restored!!.connections.map { it.tier },
    )
  }

  @Test
  fun `a stored connection keeps every field`() {
    repo.server = serverWith(listOf(lan, wan, relay))

    assertEquals(listOf(lan, wan, relay), repo.server!!.connections)
  }

  @Test
  fun `a relay connection is still a relay after a round trip`() {
    // The one that matters most for cu-11: relay must keep its penalty, or it is raced on equal
    // footing with LAN despite the extra hop and the bandwidth cap.
    repo.server = serverWith(listOf(relay))

    assertEquals(ConnectionTier.RELAY, repo.server!!.connections.single().tier)
  }

  @Test
  fun `the rest of the server survives too`() {
    repo.server = serverWith(listOf(lan))

    val restored = repo.server!!
    assertEquals("Test Server", restored.name)
    assertEquals("server-id", restored.serverId)
    assertEquals("server-token", restored.accessToken)
    assertTrue(restored.owned)
  }

  @Test
  fun `an unowned server stays unowned`() {
    repo.server = serverWith(listOf(lan)).copy(owned = false)

    assertEquals(false, repo.server!!.owned)
  }

  @Test
  fun `clearing the server removes the connections`() {
    repo.server = serverWith(listOf(lan, wan))
    repo.server = null

    assertNull(repo.server)
    // And nothing may be left behind that a later read could resurrect.
    assertTrue(
      "stale connection keys survived clearing the server",
      prefs.all.keys.none { it.contains("connection") },
    )
  }

  @Test
  fun `a server with no connections reads back as absent`() {
    // Pre-existing contract: connections.isEmpty() means there is no usable server, since
    // nothing could be reached. Preserved deliberately.
    repo.server = serverWith(emptyList())

    assertNull(repo.server)
  }

  @Test
  fun `replacing a server does not merge the old connections in`() {
    repo.server = serverWith(listOf(lan, wan, relay))
    repo.server = serverWith(listOf(wan))

    assertEquals(listOf(wan), repo.server!!.connections)
  }

  // --- Migration from the pre-cu-107 keys -------------------------------------------------
  //
  // The upgrade path matters more than it looks: an empty connection list makes `server` read
  // back as null, which presents to the user as "no server chosen" and sends them through the
  // chooser again. A fix that logged everyone out on upgrade would be worse than the bug.

  /** Writes the server the way the pre-cu-107 code did: bare URIs in two identical string sets. */
  private fun writeLegacyServer(connections: List<Connection>) {
    val uris = connections.map { it.uri }.toSet()
    prefs.edit()
      .putString("server_name", "Test Server")
      .putString("server_id", "server-id")
      .putString("server_token", "server-token")
      .putBoolean("server_owned", true)
      // Both keys got the same complete list — the names implied a partition the code never made.
      .putStringSet("local_server_connections", uris)
      .putStringSet("remote_server_connections", uris)
      .commit()
  }

  @Test
  fun `a legacy install still loads its server`() {
    writeLegacyServer(listOf(lan, wan))

    val restored = repo.server

    assertNotNull("an upgrade must not read as 'no server chosen'", restored)
    assertEquals("Test Server", restored!!.name)
    assertEquals("server-token", restored.accessToken)
    assertEquals(
      setOf(lan.uri, wan.uri),
      restored.connections.map { it.uri }.toSet(),
    )
  }

  @Test
  fun `a legacy install reads flagless, not wrongly flagged`() {
    writeLegacyServer(listOf(lan, wan))

    // The flags are genuinely unrecoverable from the old keys, so everything is DIRECT — the
    // old behaviour, preserved honestly. They are re-derived from the next /resources refresh
    // rather than guessed from the URI shape, since a wrong guess would re-introduce exactly
    // the mis-tiering this fix removes.
    assertTrue(
      repo.server!!.connections.all { it.tier == ConnectionTier.DIRECT },
    )
  }

  @Test
  fun `writing a server migrates a legacy install off the old keys`() {
    writeLegacyServer(listOf(lan, wan))

    // What `mergeServerRefresh` does on the next launch: write back the refreshed server.
    repo.server = serverWith(listOf(lan, wan, relay))

    assertEquals(
      listOf(ConnectionTier.LAN, ConnectionTier.DIRECT, ConnectionTier.RELAY),
      repo.server!!.connections.map { it.tier },
    )
    assertTrue(
      "the legacy keys must not survive, or a later read could resurrect the flagless shape",
      !prefs.contains("local_server_connections") &&
        !prefs.contains("remote_server_connections"),
    )
  }

  @Test
  fun `unreadable stored connections fall back to the legacy keys`() {
    writeLegacyServer(listOf(lan))
    // A truncated or hand-mangled value must not take the server down with it.
    prefs.edit().putString("server_connections_v2", "{not json").commit()

    val restored = repo.server

    assertNotNull("a corrupt value must not read as 'no server chosen'", restored)
    assertEquals(listOf(lan.uri), restored!!.connections.map { it.uri })
  }

  @Test
  fun `an empty stored list does not silently fall back to the legacy keys`() {
    // `[]` is a *successful* parse meaning "this server has no connections", which is a
    // different fact from "nothing has been stored yet". Falling back here would resurrect
    // connections the caller had deliberately replaced with none.
    writeLegacyServer(listOf(lan, wan))
    prefs.edit().putString("server_connections_v2", "[]").commit()

    assertNull(repo.server)
  }

  @Test
  fun `unreadable stored connections with no legacy keys read as absent`() {
    prefs.edit()
      .putString("server_name", "Test Server")
      .putString("server_id", "server-id")
      .putString("server_token", "server-token")
      .putString("server_connections_v2", "{not json")
      .commit()

    // Nothing to fall back to, so honest failure: no reachable connection means no usable
    // server, which is the pre-existing contract.
    assertNull(repo.server)
  }
}
