package io.github.mattpvaughn.chronicle.data.sources.plex

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo.LoginState
import io.github.mattpvaughn.chronicle.data.sources.plex.IPlexLoginRepo.LoginState.*
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexInterceptor.Companion.PLATFORM
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexInterceptor.Companion.PRODUCT
import io.github.mattpvaughn.chronicle.data.sources.plex.model.OAuthResponse
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexUser
import io.github.mattpvaughn.chronicle.data.sources.plex.model.UsersResponse
import io.github.mattpvaughn.chronicle.util.Event
import io.github.mattpvaughn.chronicle.util.postEvent
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

interface IPlexLoginRepo {
  /** POST the OAuth pin to start OAuth login process, and retrieve a default response */
  suspend fun postOAuthPin(): OAuthResponse?

  /**
   * Chooses a user, updates the auth token to match the user in the [PlexConfig], sets
   * [PlexPrefsRepo.user] to [responseUser], changes [loginEvent] to [LOGGED_IN_NO_SERVER_CHOSEN]
   *
   * [responseUser] must have a valid auth token
   */
  fun chooseUser(responseUser: PlexUser)

  /**
   * Chooses a server, sets it in the [PlexPrefsRepo], changes [loginEvent] to
   * [LOGGED_IN_NO_LIBRARY_CHOSEN]
   */
  fun chooseServer(serverModel: ServerModel)

  /**
   * Chooses a library, sets it in the [PlexPrefsRepo], changes state to [LOGGED_IN_FULLY].
   *
   * Returns true when this **replaced a different** library, so the caller can drop the previous
   * library's cached catalogue. A first-ever choice returns false: there is nothing to invalidate.
   */
  fun chooseLibrary(plexLibrary: PlexLibrary): Boolean

  /**
   * Determines the current [LoginState] based on information stored in [PlexPrefsRepo] and
   * updates the [loginEvent] to reflect that
   */
  fun determineLoginState()

  /**
   * Drops the credentials so the user can sign in again, keeping their chosen user, server and
   * library.
   *
   * The recovery for a token invalidated server-side. Plex has no refresh token — a new account
   * token needs a human approving an OAuth PIN in a browser — but that is the *only* thing needed,
   * so making the user re-pick a library they already picked was gratuitous. Before this the sole
   * path was a full logout (cu-84).
   */
  fun beginReauthentication()

  val loginEvent: LiveData<Event<LoginState>>

  enum class LoginState {
    NOT_LOGGED_IN,
    FAILED_TO_LOG_IN,
    LOGGED_IN_NO_USER_CHOSEN,
    LOGGED_IN_NO_SERVER_CHOSEN,
    LOGGED_IN_NO_LIBRARY_CHOSEN,
    LOGGED_IN_FULLY,
    AWAITING_LOGIN_RESULTS,
  }

  fun makeOAuthUrl(
    clientId: String,
    code: String,
  ): Uri

  suspend fun checkForOAuthAccessToken()
}

/**
 * Responsible for querying network w/r/t network data, configuring network to use login data once
 * on login succeeds, and for saving login info via [PlexPrefsRepo] on success
 */
@Singleton
class PlexLoginRepo
  @Inject
  constructor(
    private val plexPrefsRepo: PlexPrefsRepo,
    private val plexLoginService: PlexLoginService,
    private val plexConfig: PlexConfig,
    private val accountAuthState: AccountAuthState,
  ) : IPlexLoginRepo {
    private var _loginState = MutableLiveData<Event<LoginState>>()
    override val loginEvent: LiveData<Event<LoginState>>
      get() = _loginState

    override suspend fun postOAuthPin(): OAuthResponse? {
      return try {
        _loginState.postEvent(AWAITING_LOGIN_RESULTS)
        val pin = plexLoginService.postAuthPin()
        plexPrefsRepo.oAuthTempId = pin.id
        pin
      } catch (e: Throwable) {
        Timber.e(e, "Failed to log in")
        _loginState.postEvent(FAILED_TO_LOG_IN)
        null
      }
    }

    override fun beginReauthentication() {
      plexPrefsRepo.clearCredentials()
      accountAuthState.onAuthenticated()
      _loginState.postEvent(NOT_LOGGED_IN)
    }

    override fun chooseUser(responseUser: PlexUser) {
      plexPrefsRepo.user = responseUser
      _loginState.postEvent(LOGGED_IN_NO_SERVER_CHOSEN)
    }

    override fun makeOAuthUrl(
      clientId: String,
      code: String,
    ): Uri {
      // Keep [ and ] characters for readability, replace with escaped chars below
      return Uri.parse(
        (
          "https://app.plex.tv/auth#?code=$code" +
            "&context[device][product]=$PRODUCT" +
            "&context[device][environment]=bundled" +
            "&context[device][layout]=desktop" +
            "&context[device][platform]=$PLATFORM" +
            "&context[device][device]=$PRODUCT" +
            "&clientID=$clientId"
        )
          .replace("[", "%5B")
          .replace("]", "%5D"),
      )
    }

    override suspend fun checkForOAuthAccessToken() {
      val authToken =
        try {
          plexLoginService.getAuthPin(plexPrefsRepo.oAuthTempId).authToken ?: ""
        } catch (t: Throwable) {
          plexPrefsRepo.oAuthTempId = -1L
          Timber.i("Failed to get OAuth access token: ${t.message}")
          ""
        }
      if (authToken.isNotEmpty()) {
        plexPrefsRepo.accountAuthToken = authToken

        // now check if we should show user screen:
        try {
          val userResponse: UsersResponse = plexLoginService.getUsersForAccount()
          if (userResponse.users.size == 1) {
            // if there is only one user, there's no need to choose it
            chooseUser(userResponse.users[0])
          } else {
            // now we proceed to choose user
            _loginState.postEvent(LOGGED_IN_NO_USER_CHOSEN)
          }
        } catch (t: Throwable) {
          Timber.e(t, "Failed to load users, cannot proceed to profile")
        }
      }
    }

    override fun chooseServer(serverModel: ServerModel) {
      Timber.i("User chose server: $serverModel")
      plexConfig.setPotentialConnections(serverModel.connections)
      plexPrefsRepo.server = serverModel
      _loginState.postEvent(LOGGED_IN_NO_LIBRARY_CHOSEN)
    }

    /**
     * Records the chosen library, and reports whether it **replaced a different one**.
     *
     * The caller needs that answer: switching libraries invalidates every cached book and track,
     * and leaves downloads belonging to a library that is no longer selected. Settings already
     * handles this — it clears the databases and asks whether to keep downloaded files — but this
     * path did not, so choosing a different library here left the app showing a *union* of two
     * libraries until the next refresh pruned it, and a multi-gigabyte download could be reclaimed
     * later as a silent side effect of a choice nobody was warned about (cu-126).
     *
     * Returning the fact rather than acting on it keeps this repository free of database and
     * download dependencies; the decision about *what* to clear belongs with the code that already
     * owns it.
     */
    override fun chooseLibrary(plexLibrary: PlexLibrary): Boolean {
      val previous = plexPrefsRepo.library
      // A first-ever choice is not a *change*: there is nothing cached to invalidate, and
      // prompting there would ask about downloads that cannot exist yet.
      val replacedDifferentLibrary = previous != null && previous.id != plexLibrary.id
      Timber.i("User chose library: $plexLibrary (replaces different library: $replacedDifferentLibrary)")
      plexPrefsRepo.library = plexLibrary
      _loginState.postEvent(LOGGED_IN_FULLY)
      return replacedDifferentLibrary
    }

    init {
      determineLoginState()
    }

    override fun determineLoginState() {
      val token = plexPrefsRepo.accountAuthToken
      val user: PlexUser? = plexPrefsRepo.user
      val server: ServerModel? = plexPrefsRepo.server
      val library: PlexLibrary? = plexPrefsRepo.library
      // Presence, never the values: this line used to log three working credentials
      // into logcat, which persists and ends up in bug reports (cu-10).
      Timber.i(
        """Login state: hasAccountToken = ${token.isNotEmpty()},
                    |hasUserToken = ${!user?.authToken.isNullOrEmpty()},
                    |hasServerToken = ${!server?.accessToken.isNullOrEmpty()},
                    |library = ${library?.name}
        """.trimMargin(),
      )
      _loginState.postEvent(
        when {
          token.isEmpty() -> NOT_LOGGED_IN
          // A stored token is not a valid one. Plex tokens are invalidated by an event, never on a
          // timer, so presence proves nothing — and reporting LOGGED_IN_FULLY here is what made the
          // app show stale data with no way back (cu-84). Only a request that actually came back
          // 401 sets this, so being offline does not land here.
          // Deliberately *not* NOT_LOGGED_IN: that routes through `Navigator.showLogin()`, which
          // calls `plexConfig.clear()` and wipes server, library and connections — so an expired
          // token cost the user their whole configuration (decision-17, cu-73). A revoked account
          // keeps its config and its downloads; the UI surfaces `account_signed_out` and points at
          // Settings -> ACCOUNT -> "Sign in again", which already restores sync in place.
          accountAuthState.isRevoked -> {
            Timber.w("Stored token was rejected by the server; account needs re-authentication")
            LOGGED_IN_FULLY
          }
          server != null && library != null -> LOGGED_IN_FULLY // Migrating from v0.41, impossible otherwise
          user == null -> LOGGED_IN_NO_USER_CHOSEN
          server == null -> LOGGED_IN_NO_SERVER_CHOSEN
          library == null -> LOGGED_IN_NO_LIBRARY_CHOSEN
          else -> {
            Timber.i("Fully logged in branch, awaiting server checks")
            LOGGED_IN_FULLY
          }
        },
      )
    }
  }
