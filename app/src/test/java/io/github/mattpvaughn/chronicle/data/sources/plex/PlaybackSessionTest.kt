package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `PlaybackSession` — the Plex session handshake and the token that authorizes it.
 *
 * Both used to sit inside `AudiobookMediaSessionCallback`, which reached the network through
 * `Injector.get().plexMediaService()`, so neither could be tested at all. The token precedence in
 * particular was written out **twice**, here and in `ServiceModule`, with nothing checking that the
 * two copies agreed.
 */
class PlaybackSessionTest {
  private val server =
    ServerModel(
      name = "ANTARES",
      connections = emptyList(),
      serverId = "machine-1",
      accessToken = "server-token",
    )

  private fun session(
    prefs: PlexPrefsRepo,
    service: PlexMediaService = mockk(relaxed = true),
  ) = PlaybackSession(prefs, service)

  /**
   * The real in-memory fake, not a mock: these assertions are about which of three stored
   * credentials wins, so a stub that answers whatever it was told would be asserting against
   * itself.
   */
  private fun prefs(
    serverModel: ServerModel? = server,
    user: PlexUser? = null,
    accountToken: String = "",
  ): PlexPrefsRepo =
    FakePlexPrefsRepo().apply {
      this.server = serverModel
      this.user = user
      this.accountAuthToken = accountToken
    }

  @Test
  fun `the server access token wins when there is one`() {
    val token =
      session(
        prefs(user = PlexUser(authToken = "user-token"), accountToken = "account-token"),
      ).authToken

    assertEquals("server-token", token)
  }

  /**
   * The server token is scoped to the server being streamed from, so it is preferred — but a
   * `ServerModel` defaults `accessToken` to `""`, not null, and `?:` does not treat empty as
   * absent. An owned server carries no separate access token, so this is the ordinary case for a
   * user on their own server, not an edge one.
   */
  @Test
  fun `an empty server access token falls through to the account token`() {
    val token =
      session(
        prefs(serverModel = server.copy(accessToken = ""), accountToken = "account-token"),
      ).authToken

    assertEquals("account-token", token)
  }

  @Test
  fun `the profile token is used when the server has none`() {
    val token =
      session(
        prefs(
          serverModel = server.copy(accessToken = ""),
          user = PlexUser(authToken = "user-token"),
          accountToken = "account-token",
        ),
      ).authToken

    assertEquals("user-token", token)
  }

  @Test
  fun `with no server at all the account token is used`() {
    val token = session(prefs(serverModel = null, accountToken = "account-token")).authToken

    assertEquals("account-token", token)
  }

  @Test
  fun `starting a session addresses the book on the current server`() =
    runTest {
      val service = mockk<PlexMediaService>(relaxed = true)

      val started = session(prefs(), service).start("book-9")

      assertTrue(started)
      coVerify {
        service.startMediaSession(
          "server://machine-1/com.plexapp.plugins.library/library/metadata/book-9",
        )
      }
    }

  /**
   * `/playQueues` is an unofficial endpoint, and the media it would open a session for is already
   * resolved — so a failure must not cancel playback. The only cost is that the server's
   * "now playing" dashboard misses this session.
   */
  @Test
  fun `a failed handshake is reported but does not throw`() =
    runTest {
      val service =
        mockk<PlexMediaService>(relaxed = true) {
          coEvery { startMediaSession(any(), any(), any(), any(), any()) } throws IOException("503")
        }

      assertFalse(session(prefs(), service).start("book-9"))
    }

  @Test
  fun `with no server id known no handshake is attempted`() =
    runTest {
      val service = mockk<PlexMediaService>(relaxed = true)

      assertFalse(session(prefs(serverModel = null), service).start("book-9"))

      coVerify(exactly = 0) { service.startMediaSession(any(), any(), any(), any(), any()) }
    }
}
