package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * That the Ktor client actually *invokes* the re-auth plugin on a real 401, and stops at one retry.
 *
 * The counterpart of `ReauthWiringTest`, which drove the same six properties through OkHttp. That
 * file's central claim was that "retry exactly once" came from the framework: OkHttp calls an
 * `Authenticator` only on a 401 and threads `Response.priorResponse`. **Ktor gives no such
 * guarantee**, so on this side the property is ordinary code on the `Send` hook — one `proceed`,
 * then at most one more — and that makes these tests more important than their OkHttp
 * equivalents, not less. A regression here is a retry loop against plex.tv.
 *
 * `MockEngine` rather than a fake socket: the plugin's contract is about what it does with a
 * status code and a token, and the engine records every request it is handed, which is exactly
 * what "did it retry, and with what header" needs.
 */
class PlexReauthWiringTest {
  private val prefs = FakePlexPrefsRepo()

  /**
   * The token half of the headers plugin, on its own.
   *
   * The real `plexHeadersPlugin` also needs a `PlexConfig` for the identity headers and the
   * placeholder-URL rewrite, neither of which this test is about. What matters here is only that
   * the token is re-read from prefs on **every** request — because that is what makes the retry
   * observably carry a different value than the first attempt.
   */
  private fun plexHeadersTokenOnly(token: () -> String) =
    createClientPlugin("TestPlexToken") {
      onRequest { request, _ ->
        val value = token()
        if (value.isNotEmpty()) request.headers.set("X-Plex-Token", value)
      }
    }

  private fun serverWith(token: String) =
    ServerModel(
      name = "Test Server",
      connections = listOf(Connection(uri = "http://localhost", local = true)),
      serverId = "server-id",
      accessToken = token,
      owned = true,
    )

  /**
   * A client wired the way the media client is wired: the token goes on from prefs, and the
   * re-auth plugin can replace it.
   *
   * @param responses one entry per expected request, in order. Fewer entries than requests means
   *   the test has proved a retry it did not expect, and the engine throws — which is the
   *   behaviour wanted here rather than a silent default.
   */
  private fun client(
    refreshCount: AtomicInteger,
    responses: List<HttpStatusCode>,
    refreshed: () -> ServerModel?,
  ): Pair<HttpClient, MockEngine> {
    // Counted outside the lambda: `requestHistory` is not in scope inside a MockEngine handler.
    val served = AtomicInteger()
    val engine =
      MockEngine {
        val status = responses.getOrElse(served.getAndIncrement()) { HttpStatusCode.OK }
        if (status == HttpStatusCode.OK) {
          respond(content = "{}", status = HttpStatusCode.OK)
        } else {
          respondError(status)
        }
      }
    val http =
      HttpClient(engine) {
        // Stands in for the headers plugin: the token comes from prefs on every request, so the
        // retry must be seen to carry a *different* value than the first attempt.
        install(
          plexHeadersTokenOnly {
            prefs.server?.accessToken.orEmpty()
          },
        )
        install(
          plexReauthPlugin(
            plexPrefsRepo = prefs,
            accountAuthState = AccountAuthState(),
          ) {
            refreshCount.incrementAndGet()
            refreshed()
          },
        )
      }
    return http to engine
  }

  private suspend fun get(client: HttpClient): HttpResponse = client.get("http://localhost/library/sections")

  @Test
  fun `the plugin still sees a 401 when the client is configured to throw`() =
    runTest {
      // The production clients set `expectSuccess = true`, because `ProgressReporter` and the
      // account-rejection check both branch on a thrown `ResponseException` — with the Ktor 3
      // default of `false` no exception arrives, so a failed scrobble read as a success.
      //
      // This asserts the two settings do not fight. The `Send` hook runs *before* the validation
      // that raises the exception, so the plugin still gets the raw 401 and can retry; only a 401
      // that survives the retry reaches the caller, and it reaches them as an exception. Verified
      // here rather than reasoned about, because the ordering is a framework detail.
      prefs.server = serverWith("stale-token")
      val refreshes = AtomicInteger()
      val served = AtomicInteger()
      val responses = listOf(HttpStatusCode.Unauthorized, HttpStatusCode.OK)
      val engine =
        MockEngine {
          val status = responses.getOrElse(served.getAndIncrement()) { HttpStatusCode.OK }
          if (status == HttpStatusCode.OK) respond("{}", HttpStatusCode.OK) else respondError(status)
        }
      val http =
        HttpClient(engine) {
          expectSuccess = true
          install(plexHeadersTokenOnly { prefs.server?.accessToken.orEmpty() })
          install(
            plexReauthPlugin(prefs, AccountAuthState()) {
              refreshes.incrementAndGet()
              serverWith("fresh-token")
            },
          )
        }

      val response = get(http)

      assertEquals("the plugin must still be invoked on the 401", 1, refreshes.get())
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(2, engine.requestHistory.size)
    }

  @Test
  fun `a 401 that survives the retry reaches the caller as an exception`() =
    runTest {
      // The other half: with `expectSuccess = true`, an unrecoverable 401 must *throw*, or the
      // account-rejection classifier never fires and a signed-out account is never surfaced.
      prefs.server = serverWith("stale-token")
      val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
      val http =
        HttpClient(engine) {
          expectSuccess = true
          install(plexHeadersTokenOnly { prefs.server?.accessToken.orEmpty() })
          install(plexReauthPlugin(prefs, AccountAuthState()) { serverWith("fresh-token") })
        }

      val thrown = runCatching { get(http) }.exceptionOrNull()

      assertEquals(
        "an unrecoverable 401 must surface as a ResponseException",
        true,
        thrown is io.ktor.client.plugins.ResponseException,
      )
    }

  @Test
  fun `a 401 is retried with the refreshed server token`() =
    runTest {
      prefs.server = serverWith("stale-token")
      val refreshes = AtomicInteger()
      // 401 once, then accept — so success proves the retry happened.
      val (http, engine) =
        client(refreshes, listOf(HttpStatusCode.Unauthorized, HttpStatusCode.OK)) {
          serverWith("fresh-token")
        }

      val response = get(http)

      assertEquals("Ktor must invoke the plugin on a 401", 1, refreshes.get())
      assertEquals(2, engine.requestHistory.size)
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(
        "the retry must carry the refreshed token",
        "fresh-token",
        engine.requestHistory[1].headers["X-Plex-Token"],
      )
      assertEquals(
        "the first attempt must have carried the stale one",
        "stale-token",
        engine.requestHistory[0].headers["X-Plex-Token"],
      )
    }

  @Test
  fun `the refreshed server is persisted`() =
    runTest {
      prefs.server = serverWith("stale-token")
      val refreshes = AtomicInteger()
      val (http, _) =
        client(refreshes, listOf(HttpStatusCode.Unauthorized, HttpStatusCode.OK)) {
          serverWith("fresh-token")
        }

      get(http)

      assertEquals("fresh-token", prefs.server?.accessToken)
    }

  @Test
  fun `it retries exactly once and then gives up`() =
    runTest {
      // The property OkHttp gave for free and this plugin has to hold itself. A permanent 401
      // must produce exactly one refresh and exactly two requests — a loop here would hammer
      // plex.tv.
      prefs.server = serverWith("stale-token")
      val refreshes = AtomicInteger()
      val (http, engine) =
        client(
          refreshes,
          listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Unauthorized),
        ) { serverWith("fresh-token") }

      val response = get(http)

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertEquals("the Send hook must not re-enter", 1, refreshes.get())
      assertEquals("exactly two requests: the original and one retry", 2, engine.requestHistory.size)
    }

  @Test
  fun `a 200 never reaches the refresh`() =
    runTest {
      prefs.server = serverWith("good-token")
      val refreshes = AtomicInteger()
      val (http, engine) = client(refreshes, listOf(HttpStatusCode.OK)) { serverWith("fresh-token") }

      val response = get(http)

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("ordinary traffic must not trigger re-auth", 0, refreshes.get())
      assertEquals(1, engine.requestHistory.size)
    }

  @Test
  fun `a 500 never reaches the refresh`() =
    runTest {
      // Only a 401 is an auth problem. A server error retried as re-auth would both fail and
      // mislabel the cause.
      prefs.server = serverWith("good-token")
      val refreshes = AtomicInteger()
      val (http, engine) =
        client(refreshes, listOf(HttpStatusCode.InternalServerError)) { serverWith("fresh-token") }

      val response = get(http)

      assertEquals(HttpStatusCode.InternalServerError, response.status)
      assertEquals(0, refreshes.get())
      assertEquals(1, engine.requestHistory.size)
    }

  @Test
  fun `a refresh that cannot produce a server does not retry`() =
    runTest {
      // The offline case. One attempt at refreshing, no retry, and the cached server untouched —
      // being unable to reach plex.tv is not being signed out.
      prefs.server = serverWith("stale-token")
      val refreshes = AtomicInteger()
      val (http, engine) = client(refreshes, listOf(HttpStatusCode.Unauthorized)) { null }

      val response = get(http)

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertEquals(1, refreshes.get())
      assertEquals("a failed refresh must not retry", 1, engine.requestHistory.size)
      assertEquals(
        "a failed refresh must leave the cached server alone",
        "stale-token",
        prefs.server?.accessToken,
      )
    }

  @Test
  fun `an unchanged token records the account as rejected and does not retry`() =
    runTest {
      // The third outcome, and the one a binary success/failure model would lose: plex.tv answered
      // and handed back the same token, so the server token was never the problem. The account is
      // signed out, which only the user can fix.
      prefs.server = serverWith("stale-token")
      val refreshes = AtomicInteger()
      val authState = AccountAuthState()
      val engine =
        MockEngine { respondError(HttpStatusCode.Unauthorized) }
      val http =
        HttpClient(engine) {
          install(plexHeadersTokenOnly { prefs.server?.accessToken.orEmpty() })
          install(
            plexReauthPlugin(plexPrefsRepo = prefs, accountAuthState = authState) {
              refreshes.incrementAndGet()
              serverWith("stale-token")
            },
          )
        }

      http.get("http://localhost/library/sections")

      assertEquals(1, refreshes.get())
      assertEquals("an unchanged token must not retry", 1, engine.requestHistory.size)
      assertEquals(
        "the account must be recorded as revoked so the UI can offer re-auth",
        AccountAuthState.State.Revoked,
        authState.state.value,
      )
    }

  @Test
  fun `an empty refreshed token is treated as unchanged`() =
    runTest {
      prefs.server = serverWith("stale-token")
      val refreshes = AtomicInteger()
      val (http, engine) = client(refreshes, listOf(HttpStatusCode.Unauthorized)) { serverWith("") }

      get(http)

      assertEquals(1, refreshes.get())
      assertEquals("an empty token is absent, not a new credential", 1, engine.requestHistory.size)
      assertNull(
        "the cached server must not be overwritten with a tokenless one",
        prefs.server?.accessToken?.takeIf { it.isEmpty() },
      )
    }
}
