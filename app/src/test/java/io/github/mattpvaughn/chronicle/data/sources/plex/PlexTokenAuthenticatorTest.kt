package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 401 recovery contract.
 *
 * Plex has no refresh token. An *account* token can only be replaced by a human approving
 * an OAuth PIN, so nothing here can recover one. A *server* access token, though, can be
 * re-fetched from `/api/v2/resources` with the account token — and that covers the case
 * that actually bites: a server re-claimed or its token rotated, leaving a stale value
 * cached here while the account is still fine.
 *
 * The cases below are mostly about *not* retrying. A 401 loop against plex.tv would drain
 * the battery and hammer someone else's service, so every path that cannot succeed has to
 * stop deliberately.
 */
class PlexTokenAuthenticatorTest {
  private val prefs =
    mockk<PlexPrefsRepo>(relaxed = true) {
      every { server } returns cachedServer(STALE_TOKEN)
    }

  @Test
  fun `a 401 refreshes the server token and retries with it`() {
    val authenticator = authenticator { cachedServer(FRESH_TOKEN) }

    val retried = authenticator.authenticate(null, response401())

    assertEquals(FRESH_TOKEN, retried?.header("X-Plex-Token"))
  }

  @Test
  fun `a successful refresh is persisted`() {
    val refreshed = cachedServer(FRESH_TOKEN)
    val authenticator = authenticator { refreshed }

    authenticator.authenticate(null, response401())

    verify { prefs.server = refreshed }
  }

  @Test
  fun `it gives up rather than retrying forever`() {
    val authenticator = authenticator { error("refresh must not be attempted twice") }

    // priorResponse present = OkHttp already retried this request once.
    val retried = authenticator.authenticate(null, response401(hasPriorResponse = true))

    assertNull("a second 401 means the refreshed token is bad too; stop", retried)
  }

  @Test
  fun `an unchanged token is not worth replaying`() {
    val authenticator = authenticator { cachedServer(STALE_TOKEN) }

    assertNull(
      "Plex returned the same token, so the 401 is about the account, not the server",
      authenticator.authenticate(null, response401()),
    )
  }

  @Test
  fun `an empty refreshed token does not trigger a retry`() {
    val authenticator = authenticator { cachedServer("") }

    assertNull(authenticator.authenticate(null, response401()))
  }

  @Test
  fun `a failed refresh does not retry`() {
    val authenticator = authenticator { null }

    assertNull(authenticator.authenticate(null, response401()))
  }

  @Test
  fun `a throwing refresh does not propagate`() {
    val authenticator = authenticator { throw java.io.IOException("offline") }

    assertNull(
      "an exception here would surface as a confusing failure on the original call",
      authenticator.authenticate(null, response401()),
    )
  }

  /**
   * plex.tv answered and still refused: the account token itself is dead. Recorded so the UI can
   * say so and offer re-authentication, instead of the app presenting stale data in silence (cu-84).
   */
  @Test
  fun `an unchanged token records the account as signed out`() {
    val authenticator = authenticator { cachedServer(STALE_TOKEN) }

    authenticator.authenticate(null, response401())

    assertTrue(
      "a surviving 401 is the only reliable signal that the account is signed out",
      accountAuthState.isSignedOut.value,
    )
  }

  /**
   * The distinction that keeps this honest. A refresh can fail because the network is gone, and
   * being offline is **not** being signed out — claiming otherwise would tell every user on a train
   * that their account is dead.
   */
  @Test
  fun `a failed refresh does not record the account as signed out`() {
    val authenticator = authenticator { null }

    authenticator.authenticate(null, response401())

    assertFalse(
      "offline must not be reported as signed out",
      accountAuthState.isSignedOut.value,
    )
  }

  @Test
  fun `a throwing refresh does not record the account as signed out`() {
    val authenticator = authenticator { throw java.io.IOException("offline") }

    authenticator.authenticate(null, response401())

    assertFalse(accountAuthState.isSignedOut.value)
  }

  /** A successful refresh means the account is fine, so an earlier signal must be cleared. */
  @Test
  fun `a successful refresh clears a previous signed-out state`() {
    accountAuthState.onAccountRejected()
    val authenticator = authenticator { cachedServer("a-fresh-token") }

    authenticator.authenticate(null, response401())

    assertFalse(accountAuthState.isSignedOut.value)
  }

  /** Giving up after a prior retry says nothing new; it must not manufacture a signal. */
  @Test
  fun `a second 401 on the same call does not record a signed-out state`() {
    val authenticator = authenticator { cachedServer("a-fresh-token") }

    authenticator.authenticate(null, response401(hasPriorResponse = true))

    assertFalse(accountAuthState.isSignedOut.value)
  }

  private val accountAuthState = AccountAuthState()

  private fun authenticator(refresh: suspend () -> ServerModel?) = PlexTokenAuthenticator(prefs, accountAuthState, refresh)

  private fun response401(hasPriorResponse: Boolean = false): Response {
    val request =
      Request.Builder().url("https://example.plex.direct/library/sections").build()
    val builder =
      Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(401)
        .message("Unauthorized")
    if (hasPriorResponse) {
      builder.priorResponse(
        Response.Builder()
          .request(request)
          .protocol(Protocol.HTTP_1_1)
          .code(401)
          .message("Unauthorized")
          .build(),
      )
    }
    return builder.build()
  }

  private companion object {
    const val STALE_TOKEN = "stale-token"
    const val FRESH_TOKEN = "fresh-token"

    fun cachedServer(token: String) =
      ServerModel(
        name = "Server",
        connections = emptyList(),
        serverId = "abc",
        accessToken = token,
      )
  }
}
