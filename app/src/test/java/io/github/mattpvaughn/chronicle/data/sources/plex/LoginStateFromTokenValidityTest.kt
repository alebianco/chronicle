package io.github.mattpvaughn.chronicle.data.sources.plex

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.LOGGED_IN_FULLY
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.LOGGED_IN_NO_SERVER_CHOSEN
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.NOT_LOGGED_IN
import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.github.mattpvaughn.chronicle.data.sources.plex.model.MediaType
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexUser
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Whether a stored token is treated as a *valid* one.
 *
 * `determineLoginState` decided purely on presence: a non-empty token plus a chosen server and
 * library meant `LOGGED_IN_FULLY`. Plex tokens are invalidated by an **event** — a password change
 * with "sign out connected devices", a server re-claim — never on a timer, so a stored token can be
 * perfectly well-formed and completely dead. The app then showed stale data or an empty library and
 * said nothing (cu-84).
 *
 * This is the branch that decides whether the user is told. The authenticator's side of the same
 * fix is in `PlexTokenAuthenticatorTest`; what is pinned here is that the signal is *acted on*.
 */
class LoginStateFromTokenValidityTest {
  @get:Rule
  val instantTaskExecutorRule = InstantTaskExecutorRule()

  private val server =
    ServerModel(
      name = "Tower",
      connections = listOf(Connection(uri = "https://192-168-1-7.abc.plex.direct:32400", local = true)),
      serverId = "server-1",
      accessToken = "server-token",
    )
  private val library = PlexLibrary(name = "Audiobooks", type = MediaType.ALBUM, id = "12")
  private val user = PlexUser(id = 7L, uuid = "u-7", title = "Reader", authToken = "user-token")

  private fun repo(
    prefs: PlexPrefsRepo,
    authState: AccountAuthState = AccountAuthState(),
  ) = PlexLoginRepo(
    plexPrefsRepo = prefs,
    plexLoginService = mockk(relaxed = true),
    plexConfig = mockk(relaxed = true),
    accountAuthState = authState,
  )

  private fun fullySetUpPrefs() =
    FakePlexPrefsRepo().apply {
      accountAuthToken = "account-token"
      server = this@LoginStateFromTokenValidityTest.server
      library = this@LoginStateFromTokenValidityTest.library
      user = this@LoginStateFromTokenValidityTest.user
    }

  @Test
  fun `a valid token with a server and library is fully logged in`() {
    val repo = repo(fullySetUpPrefs())

    repo.determineLoginState()

    assertEquals(LOGGED_IN_FULLY, repo.loginEvent.value?.peekContent())
  }

  /** The regression: presence is not validity. */
  @Test
  fun `a token the server has rejected is not logged in`() {
    val authState = AccountAuthState().apply { onAccountRejected() }
    val repo = repo(fullySetUpPrefs(), authState)

    repo.determineLoginState()

    assertEquals(
      "a rejected token must not report as logged in, or the app shows stale data in silence",
      NOT_LOGGED_IN,
      repo.loginEvent.value?.peekContent(),
    )
  }

  /**
   * Recovery must be believed. Once a fresh token is accepted, the app has to return to normal
   * without a restart.
   */
  @Test
  fun `clearing the rejection restores the fully logged in state`() {
    val authState = AccountAuthState().apply { onAccountRejected() }
    val repo = repo(fullySetUpPrefs(), authState)
    repo.determineLoginState()

    authState.onAuthenticated()
    repo.determineLoginState()

    assertEquals(LOGGED_IN_FULLY, repo.loginEvent.value?.peekContent())
  }

  @Test
  fun `an absent token is not logged in regardless of the auth state`() {
    val repo = repo(FakePlexPrefsRepo())

    repo.determineLoginState()

    assertEquals(NOT_LOGGED_IN, repo.loginEvent.value?.peekContent())
  }

  /**
   * The incomplete-setup branches must still work: a rejected token short-circuits to
   * `NOT_LOGGED_IN`, but a *valid* token with a missing choice must still ask for that choice
   * rather than being swept into the same state.
   */
  @Test
  fun `a valid token with no server asks for a server`() {
    val prefs =
      FakePlexPrefsRepo().apply {
        accountAuthToken = "account-token"
        user = this@LoginStateFromTokenValidityTest.user
      }
    val repo = repo(prefs)

    repo.determineLoginState()

    assertEquals(LOGGED_IN_NO_SERVER_CHOSEN, repo.loginEvent.value?.peekContent())
  }

  /** After re-auth the credentials are gone, so the app must route to login. */
  @Test
  fun `after beginReauthentication the state is not logged in`() {
    val prefs = fullySetUpPrefs()
    val repo = repo(prefs)

    repo.beginReauthentication()

    assertEquals(NOT_LOGGED_IN, repo.loginEvent.value?.peekContent())
    assertEquals("", prefs.accountAuthToken)
  }

  /** ...but it must not have thrown away the setup the user already completed. */
  @Test
  fun `beginReauthentication keeps the server and library`() {
    val prefs = fullySetUpPrefs()
    val repo = repo(prefs)

    repo.beginReauthentication()

    assertEquals("server-1", prefs.server?.serverId)
    assertEquals("12", prefs.library?.id)
  }

  /** Re-auth also clears the stale rejection, so the retry is not pre-judged. */
  @Test
  fun `beginReauthentication clears a previous rejection`() {
    val authState = AccountAuthState().apply { onAccountRejected() }
    val repo = repo(fullySetUpPrefs(), authState)

    repo.beginReauthentication()

    assertEquals(false, authState.isSignedOut.value)
  }
}
