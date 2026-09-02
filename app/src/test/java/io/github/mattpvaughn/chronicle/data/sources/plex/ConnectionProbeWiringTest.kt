package io.github.mattpvaughn.chronicle.data.sources.plex

import com.squareup.moshi.Moshi
import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.github.mattpvaughn.chronicle.data.sources.plex.model.ConnectionTier
import io.github.mattpvaughn.chronicle.data.sources.plex.model.tier
import io.github.mattpvaughn.chronicle.testing.FakePlexServer
import io.github.mattpvaughn.chronicle.util.TestDispatcherProvider
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * The real `checkServer` probe, driven through Retrofit against a fake server (cu-73, cu-11).
 *
 * [ConnectionChooserTest] injects the probe lambda — deliberately, and its KDoc says so:
 * "*Injected so this is testable without Retrofit; production passes a `checkServer` call.*"
 * That leaves the production lambda itself, `plexMediaService.checkServer(uri).isSuccessful`,
 * covered only by the Dagger graph resolving.
 *
 * Two things can be wrong in that one line and no existing test would notice: the endpoint could
 * 404 (it is `{url}/identity` with an *encoded* path parameter, which is easy to break), and
 * `isSuccessful` could be true for a response that is not a usable server. Both would present as
 * "the app cannot connect", with the chooser's own tests all green — the [[cu-107]] shape of
 * blind spot.
 *
 * So these use the real `PlexMediaService` interface over the real Retrofit + Moshi stack, and
 * make the tier decisions on real HTTP responses.
 */
class ConnectionProbeWiringTest {
  @get:Rule
  val plex = FakePlexServer()

  // The test's own scheduler, so the chooser's tier budget runs on virtual time — matching
  // how ConnectionChooserTest builds it.
  private fun TestScope.chooser() = ConnectionChooser(TestDispatcherProvider(testScheduler))

  private fun service(): PlexMediaService =
    Retrofit.Builder()
      .baseUrl(plex.url)
      .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
      .build()
      .create(PlexMediaService::class.java)

  /** Exactly the lambda `PlexConfig.chooseViableConnections` passes in production. */
  private suspend fun probe(connection: Connection): Boolean = service().checkServer(connection.uri).isSuccessful

  private fun connection(
    path: String,
    local: Boolean = false,
    relay: Boolean = false,
  ) = Connection(uri = "${plex.url}$path", local = local, relay = relay)

  @Test
  fun `the real probe succeeds against a live identity endpoint`() =
    runTest {
      // If `{url}/identity` were malformed or the converter could not parse the body, this
      // returns false and the app is simply unreachable — with every chooser test still green.
      assertTrue(probe(connection("")))
    }

  @Test
  fun `the probe actually calls identity`() =
    runTest {
      probe(connection(""))

      assertTrue(
        "expected an /identity request, got ${plex.requestedPaths}",
        plex.requestedPaths.any { it.endsWith("/identity") },
      )
    }

  @Test
  fun `a 401 is not a usable server`() =
    runTest {
      // isSuccessful is false for 4xx, so a signed-out account must not read as reachable.
      plex.stubUnauthorized("/identity")

      assertEquals(false, probe(connection("")))
    }

  @Test
  fun `a 500 is not a usable server`() =
    runTest {
      plex.stubFailure("/identity", 500)

      assertEquals(false, probe(connection("")))
    }

  @Test
  fun `the chooser picks a LAN connection over WAN using the real probe`() =
    runTest {
      // End-to-end: real tiering decision, real HTTP responses.
      //
      // Only the LAN address is reachable here, and that is deliberate. With *both* reachable
      // the winner is decided by which `Deferred` settles first in `awaitFirstSuccess`'s
      // `select`, and on an unconfined test dispatcher two real suspending HTTP calls settle in
      // a nondeterministic order — so such a test would assert scheduling luck, not preference.
      // Tier *preference* with instant probes is already covered by `ConnectionChooserTest`
      // ("LAN is preferred when every tier works"); what is unique here is that the winner was
      // selected by the real `checkServer` call rather than an injected lambda.
      val lan = connection("/lan", local = true)
      val wan = Connection(uri = "http://127.0.0.1:1/dead", local = false)

      val chosen = chooser().choose(listOf(wan, lan)) { probe(it) }

      assertNotNull(chosen)
      assertEquals(ConnectionTier.LAN, chosen!!.tier)
      assertTrue(
        "the winner must be the LAN address, chosen via the real probe",
        chosen.uri.endsWith("/lan"),
      )
      assertTrue(
        "the real probe must have been what answered",
        plex.requestedPaths.any { it.startsWith("/lan") && it.endsWith("/identity") },
      )
    }

  @Test
  fun `a dead LAN address falls through to a reachable WAN one`() =
    runTest {
      // The case cu-73 hit for real on a router with broken DNS: LAN is offered but cannot be
      // reached, and the app must still connect.
      val lan = Connection(uri = "http://127.0.0.1:1/dead", local = true)
      val wan = connection("")

      val chosen = chooser().choose(listOf(lan, wan)) { probe(it) }

      assertNotNull("a dead LAN address must not strand the app offline", chosen)
      assertEquals(ConnectionTier.DIRECT, chosen!!.tier)
    }

  @Test
  fun `nothing reachable returns null rather than a false success`() =
    runTest {
      plex.stubFailure("/identity", 503)

      assertNull(chooser().choose(listOf(connection("", local = true))) { probe(it) })
    }

  @Test
  fun `a relay connection is still chosen when it is all there is`() =
    runTest {
      // Relay is penalised, not banned — cu-11's last-tier rule is what keeps this working.
      val chosen = chooser().choose(listOf(connection("", relay = true))) { probe(it) }

      assertNotNull(chosen)
      assertEquals(ConnectionTier.RELAY, chosen!!.tier)
    }

  @Test
  fun `an empty body still counts as reachable`() =
    runTest {
      // /identity is a liveness check. A 200 with nothing useful in it still means the server
      // answered, and Moshi must not turn that into a failure.
      plex.stub("/identity", MockResponse().setResponseCode(200).setBody("{}"))

      assertTrue(probe(connection("")))
    }
}
