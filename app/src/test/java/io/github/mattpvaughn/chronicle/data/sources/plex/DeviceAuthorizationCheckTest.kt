package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.sources.plex.AccountAuthState.State
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexDevice
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * The proactive half of decision-17.
 *
 * The load-bearing property is **not** that revocation is detected — it is that everything else is
 * *not* mistaken for revocation. Removing a device invalidates no token (measured: 111 requests,
 * all `200`, after the owner deleted it), so the app has to ask; and asking badly would
 * reintroduce cu-84, where being offline was reported as being signed out.
 */
class DeviceAuthorizationCheckTest {
  private val thisDevice = "758e3323-02cc-4c46-98b9-29aa6d90f251"

  private fun device(id: String) = PlexDevice(clientIdentifier = id, name = "Chronicle", product = "Chronicle")

  private fun check(
    authState: AccountAuthState,
    uuid: String = thisDevice,
    stub: suspend () -> List<PlexDevice>,
  ): DeviceAuthorizationCheck {
    val service = mockk<PlexLoginService>()
    coEvery { service.devices() } coAnswers { stub() }
    val prefs = mockk<PlexPrefsRepo>()
    every { prefs.uuid } returns uuid
    return DeviceAuthorizationCheck(service, prefs, authState)
  }

  @Test
  fun `a device still listed is authenticated`() =
    runTest {
      val authState = AccountAuthState()

      check(authState) { listOf(device("other"), device(thisDevice)) }.run()

      assertEquals(State.Authenticated, authState.state.value)
    }

  @Test
  fun `a device missing from a successful list is revoked`() =
    runTest {
      val authState = AccountAuthState()

      // The account still has devices — other installs — but not this one.
      check(authState) { listOf(device("other-1"), device("other-2")) }.run()

      assertEquals(State.Revoked, authState.state.value)
    }

  @Test
  fun `matching is on the client identifier, not the device name`() =
    runTest {
      // Every login mints a new identifier, so an account accumulates several rows with the *same*
      // name. Matching on name would keep a revoked install alive as long as any namesake remains.
      val authState = AccountAuthState()

      check(authState) {
        listOf(
          PlexDevice(clientIdentifier = "stale-1", name = "Phh-Treble vanilla"),
          PlexDevice(clientIdentifier = "stale-2", name = "Phh-Treble vanilla"),
        )
      }.run()

      assertEquals(State.Revoked, authState.state.value)
    }

  /** cu-84's rule. Each of these must leave the state alone, never Revoked. */
  @Test
  fun `being offline is not revocation`() =
    runTest {
      val authState = AccountAuthState().apply { onAuthenticated() }

      check(authState) { throw IOException("no network") }.run()

      assertEquals(State.Authenticated, authState.state.value)
    }

  @Test
  fun `a timeout is not revocation`() =
    runTest {
      val authState = AccountAuthState().apply { onAuthenticated() }

      check(authState) { throw SocketTimeoutException("timed out") }.run()

      assertEquals(State.Authenticated, authState.state.value)
    }

  @Test
  fun `a malformed response is not revocation`() =
    runTest {
      val authState = AccountAuthState().apply { onAuthenticated() }

      check(authState) { throw IllegalStateException("unparseable body") }.run()

      assertEquals(State.Authenticated, authState.state.value)
    }

  @Test
  fun `an empty device list is treated as inconclusive, not revocation`() =
    runTest {
      // A successful call cannot legitimately return zero devices while this one is making it, so
      // it is likelier a shape change than a mass revocation — and concluding otherwise would sign
      // every user out at once.
      val authState = AccountAuthState().apply { onAuthenticated() }

      check(authState) { emptyList() }.run()

      assertEquals(State.Authenticated, authState.state.value)
    }

  @Test
  fun `a failure never resurrects a known revocation`() =
    runTest {
      val authState = AccountAuthState().apply { onDeviceRevoked() }

      check(authState) { throw IOException("no network") }.run()

      assertEquals(
        "a failed check is not evidence the revocation was lifted",
        State.Revoked,
        authState.state.value,
      )
    }

  @Test
  fun `a device that reappears clears the revocation`() =
    runTest {
      // Signing in again re-registers the client; the app must return to normal without a restart.
      val authState = AccountAuthState().apply { onDeviceRevoked() }

      check(authState) { listOf(device(thisDevice)) }.run()

      assertEquals(State.Authenticated, authState.state.value)
    }

  @Test
  fun `no stored identifier is inconclusive`() =
    runTest {
      // Before the first login there is nothing to match, and claiming revocation would show a
      // signed-out message to someone who has never signed in.
      val authState = AccountAuthState()

      check(authState, uuid = "") { listOf(device("other")) }.run()

      assertEquals(State.Unknown, authState.state.value)
    }
}
