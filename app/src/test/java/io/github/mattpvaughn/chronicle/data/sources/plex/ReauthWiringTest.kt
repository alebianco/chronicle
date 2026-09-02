package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.github.mattpvaughn.chronicle.testing.FakePlexServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * That OkHttp actually *invokes* [PlexTokenAuthenticator] on a real 401 (cu-73, cu-10).
 *
 * [PlexTokenAuthenticatorTest] is the thorough one, but every case there calls
 * `authenticator.authenticate(...)` **directly**. That covers the decision and not the wiring, and
 * the class's own KDoc leans on the framework for its central property: "an [okhttp3.Authenticator]
 * rather than an [okhttp3.Interceptor] because OkHttp invokes it only on a 401 and threads the
 * previous attempt through `Response.priorResponse` — which is what makes 'retry exactly once' a
 * property of the framework rather than hand-rolled state."
 *
 * A direct call cannot verify any of that. `priorResponse` is populated by OkHttp, so a test that
 * hand-builds a response is asserting against its own fixture: the retry-once guard could be
 * removed, or the authenticator never attached at all, and those tests would still pass. cu-107 was
 * exactly this shape of blind spot — correct logic, untested seam — so these drive a real client
 * against a real (fake) server instead.
 */
class ReauthWiringTest {
  @get:Rule
  val plex = FakePlexServer()

  private val prefs = FakePlexPrefsRepo()

  private fun serverWith(token: String) =
    ServerModel(
      name = "Test Server",
      connections = listOf(Connection(uri = "http://localhost", local = true)),
      serverId = "server-id",
      accessToken = token,
      owned = true,
    )

  /**
   * A client wired the way `AppModule.mediaOkHttpClient` wires it: the token goes on via an
   * interceptor reading prefs (as `PlexInterceptor` does), and the authenticator can replace it.
   */
  private fun client(
    refreshCount: AtomicInteger,
    refreshed: () -> ServerModel?,
  ): OkHttpClient =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        val token = prefs.server?.accessToken.orEmpty()
        chain.proceed(
          chain.request().newBuilder()
            .apply { if (token.isNotEmpty()) header("X-Plex-Token", token) }
            .build(),
        )
      }
      .authenticator(
        PlexTokenAuthenticator(
          plexPrefsRepo = prefs,
          accountAuthState = AccountAuthState(),
        ) {
          refreshCount.incrementAndGet()
          refreshed()
        },
      )
      .build()

  private fun get(client: OkHttpClient) = client.newCall(Request.Builder().url("${plex.url}/library/sections").build()).execute()

  @Test
  fun `a 401 is retried with the refreshed server token`() {
    prefs.server = serverWith("stale-token")
    val refreshes = AtomicInteger()
    // 401 once, then accept whatever comes next — so success proves the *retry* happened.
    plex.stubUnauthorized("/library/sections")
    val c = client(refreshes) { serverWith("fresh-token") }

    plex.stub("/library/sections", MockResponse().setResponseCode(401))
    val response = get(c)
    response.close()

    assertEquals("OkHttp must invoke the authenticator on a 401", 1, refreshes.get())
    assertTrue(
      "the retry must carry the refreshed token",
      plex.requestedPaths.count { it.startsWith("/library/sections") } >= 2,
    )
  }

  @Test
  fun `the refreshed token is sent on the retry, not the stale one`() {
    prefs.server = serverWith("stale-token")
    val refreshes = AtomicInteger()
    plex.stub("/library/sections", MockResponse().setResponseCode(401))
    val c = client(refreshes) { serverWith("fresh-token") }

    get(c).close()

    // The authenticator persists the refreshed server, which is what the next request reads.
    assertEquals("fresh-token", prefs.server?.accessToken)
  }

  @Test
  fun `it retries exactly once and then gives up`() {
    // The property the direct tests cannot reach: OkHttp threads priorResponse, and that is
    // what stops a loop. A permanent 401 must therefore produce exactly one refresh.
    prefs.server = serverWith("stale-token")
    val refreshes = AtomicInteger()
    plex.stub("/library/sections", MockResponse().setResponseCode(401))
    val c = client(refreshes) { serverWith("fresh-token") }

    val response = get(c)
    val code = response.code
    response.close()

    assertEquals(401, code)
    assertEquals(
      "a retry loop here would hammer plex.tv; OkHttp's priorResponse must stop it at one",
      1,
      refreshes.get(),
    )
  }

  @Test
  fun `a 200 never reaches the authenticator`() {
    prefs.server = serverWith("good-token")
    val refreshes = AtomicInteger()
    val c = client(refreshes) { serverWith("fresh-token") }

    val response = get(c)
    val code = response.code
    response.close()

    assertEquals(200, code)
    assertEquals("ordinary traffic must not trigger re-auth", 0, refreshes.get())
  }

  @Test
  fun `a 500 never reaches the authenticator`() {
    // Only a 401 is an auth problem. A server error retried as re-auth would both fail and
    // mislabel the cause.
    prefs.server = serverWith("good-token")
    val refreshes = AtomicInteger()
    plex.stubFailure("/library/sections", 500)
    val c = client(refreshes) { serverWith("fresh-token") }

    val response = get(c)
    val code = response.code
    response.close()

    assertEquals(500, code)
    assertEquals(0, refreshes.get())
  }

  @Test
  fun `a refresh that cannot produce a server does not retry`() {
    prefs.server = serverWith("stale-token")
    val refreshes = AtomicInteger()
    plex.stub("/library/sections", MockResponse().setResponseCode(401))
    val c = client(refreshes) { null }

    val response = get(c)
    val code = response.code
    response.close()

    assertEquals(401, code)
    assertEquals(1, refreshes.get())
    assertEquals(
      "a failed refresh must leave the cached server alone",
      "stale-token",
      prefs.server?.accessToken,
    )
  }

  @Test
  fun `an unchanged token is not replayed`() {
    // Re-sending a token the server just rejected is a guaranteed second 401. The account is
    // signed out, and that must be recorded rather than retried.
    prefs.server = serverWith("stale-token")
    val refreshes = AtomicInteger()
    val authState = AccountAuthState()
    plex.stub("/library/sections", MockResponse().setResponseCode(401))
    val c =
      OkHttpClient.Builder()
        .addInterceptor { chain ->
          chain.proceed(
            chain.request().newBuilder()
              .header("X-Plex-Token", prefs.server?.accessToken.orEmpty())
              .build(),
          )
        }
        .authenticator(
          PlexTokenAuthenticator(prefs, authState) {
            refreshes.incrementAndGet()
            serverWith("stale-token")
          },
        )
        .build()

    val response = c.newCall(Request.Builder().url("${plex.url}/library/sections").build()).execute()
    response.close()

    assertEquals(1, refreshes.get())
    assertTrue(
      "an unrecoverable 401 must surface as a signed-out account",
      authState.isRevoked == true,
    )
  }
}
