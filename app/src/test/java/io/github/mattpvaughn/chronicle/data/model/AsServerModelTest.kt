package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PlexServer.asServer()` — the conversion at the root of the empty-token defect, and previously
 * uncovered.
 *
 * The line that matters is `accessToken = this.accessToken ?: ""`. It is why an absent token
 * becomes an **empty string** rather than staying null, and why two call sites that resolved the
 * token with `?:` silently authorised every media request with an empty `X-Plex-Token`: a server
 * the user owns ordinarily reports no token of its own, so `""` won the elvis over a perfectly
 * good account token.
 *
 * The conversion itself is correct and stays — `ServerModel.accessToken` is non-null, and
 * `mergeServers` relies on `ifEmpty` to mean "no token" — but the empty-not-null behaviour is now
 * pinned, because the whole precedence chain in `PlaybackSession.authToken` is built on it.
 */
class AsServerModelTest {
  private fun plexServer(token: String? = "server-token") =
    PlexServer(
      name = "ANTARES",
      connections = listOf(Connection(uri = "https://192-168-1-7.hash.plex.direct")),
      clientIdentifier = "abc123",
      accessToken = token,
      owned = true,
    )

  @Test
  fun `every field is carried across`() {
    val model = plexServer().asServer()

    assertEquals("ANTARES", model.name)
    assertEquals("abc123", model.serverId)
    assertEquals("server-token", model.accessToken)
    assertTrue(model.owned)
    assertEquals(1, model.connections.size)
  }

  /**
   * The empty-token shape, stated as a fact rather than a hope.
   *
   * A null token must become `""`, because `ServerModel.accessToken` is non-null and every reader
   * downstream tests emptiness rather than nullity. Anything that changed this to a nullable field
   * without also revisiting `PlaybackSession.authToken` and `mergeServers`' `ifEmpty` would
   * reintroduce the defect in a new form.
   */
  @Test
  fun `an absent token becomes empty rather than null`() {
    val model = plexServer(token = null).asServer()

    assertEquals("", model.accessToken)
    assertTrue(
      "an empty token must read as absent, which is what `ifEmpty` and the auth precedence rely on",
      model.accessToken.isEmpty(),
    )
  }

  @Test
  fun `an empty token stays empty`() {
    assertEquals("", plexServer(token = "").asServer().accessToken)
  }

  /**
   * The ordinary case for a server the user owns: Plex reports no per-server token, and the
   * account token is expected to carry the request instead. Pinned because this *is* the common
   * path, and it was the one nobody had exercised.
   */
  @Test
  fun `an owned server reporting no token converts without inventing one`() {
    val model = PlexServer(name = "home", clientIdentifier = "id", accessToken = null).asServer()

    assertEquals("", model.accessToken)
    assertEquals("id", model.serverId)
  }
}
