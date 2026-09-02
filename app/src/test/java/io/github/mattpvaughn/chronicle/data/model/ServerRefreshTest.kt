package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Folding a `/api/v2/resources` refresh into the cached server.
 *
 * The launch path already re-fetched resources but kept only `.flatMap { it.connections }`,
 * discarding the `accessToken` that `asServer()` carries — so a rotated server token was
 * fetched and thrown away every launch, and the app kept using a stale one until something
 * returned 401. It also appended connections with no dedupe, growing a list that
 * `chooseViableConnections` races in parallel.
 */
class ServerRefreshTest {
  private val cached =
    ServerModel(
      name = "Server",
      connections = listOf(LOCAL),
      serverId = "abc",
      accessToken = "old-token",
    )

  @Test
  fun `a refreshed access token replaces the cached one`() {
    val fetched = cached.copy(accessToken = "new-token")

    assertEquals(
      "picking up the rotated token is the entire reason to re-fetch resources",
      "new-token",
      mergeServerRefresh(cached, fetched).accessToken,
    )
  }

  @Test
  fun `an empty fetched token does not wipe a working one`() {
    val fetched = cached.copy(accessToken = "")

    assertEquals(
      "a shared server can legitimately report no token; keep what still works",
      "old-token",
      mergeServerRefresh(cached, fetched).accessToken,
    )
  }

  @Test
  fun `a failed fetch leaves the cached server untouched`() {
    assertEquals(
      "an offline launch must not degrade stored credentials",
      cached,
      mergeServerRefresh(cached, null),
    )
  }

  @Test
  fun `connections are merged without duplicates`() {
    val fetched = cached.copy(connections = listOf(LOCAL, REMOTE))

    assertEquals(
      "appending blindly grew this list on every launch",
      listOf(LOCAL, REMOTE),
      mergeServerRefresh(cached, fetched).connections,
    )
  }

  @Test
  fun `freshly reported connections are tried first`() {
    val fetched = cached.copy(connections = listOf(FRESH))

    assertEquals(
      "connections are raced in order, and a just-reported address is likelier to answer",
      FRESH,
      mergeServerRefresh(cached, fetched).connections.first(),
    )
  }

  @Test
  fun `a cached connection missing from the refresh is retained`() {
    val fetched = cached.copy(connections = listOf(REMOTE))

    assertEquals(
      "resources can omit a LAN address the device can still reach",
      listOf(REMOTE, LOCAL),
      mergeServerRefresh(cached, fetched).connections,
    )
  }

  @Test
  fun `the same address cached with different flags is not duplicated`() {
    // The case that appears right after the cu-107 migration: the cached copy carries no
    // flags, because the pre-cu-107 keys stored bare URIs, while the refresh reports the
    // real ones. Whole-object equality kept both, which put one address in two tiers and
    // made `ConnectionChooser` probe it twice per selection round.
    val flagless = Connection(LOCAL.uri)
    val cachedFlagless = cached.copy(connections = listOf(flagless))
    val fetched = cached.copy(connections = listOf(LOCAL))

    assertEquals(
      "one address must appear once, with the freshly reported flags",
      listOf(LOCAL),
      mergeServerRefresh(cachedFlagless, fetched).connections,
    )
  }

  @Test
  fun `the fetched flags win over the cached ones`() {
    // Ordering does double duty: fetched-first plus dedupe-by-URI means the refresh is
    // authoritative about a connection's tier, which is what makes cu-107's re-derivation work.
    val staleFlags = Connection(LOCAL.uri, local = false, relay = true)
    val cachedStale = cached.copy(connections = listOf(staleFlags))
    val fetched = cached.copy(connections = listOf(LOCAL))

    assertEquals(LOCAL, mergeServerRefresh(cachedStale, fetched).connections.single())
  }

  private companion object {
    val LOCAL = Connection("https://10-0-0-42.hash.plex.direct:32400", local = true)
    val REMOTE = Connection("https://remote.hash.plex.direct:32400", local = false)
    val FRESH = Connection("https://10-0-0-99.hash.plex.direct:32400", local = true)
  }
}
