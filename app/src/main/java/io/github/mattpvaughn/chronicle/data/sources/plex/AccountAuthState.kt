package io.github.mattpvaughn.chronicle.data.sources.plex

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the Plex account is still signed in, as observed from actual request failures.
 *
 * `PlexLoginRepo.determineLoginState` decides from **presence** — a non-empty stored token means
 * `LOGGED_IN_FULLY`. Plex tokens never expire on a timer; they are invalidated by an event (a
 * password change with "sign out connected devices", a server re-claim), so a stored token can be
 * dead while looking perfectly valid. The app then showed stale data or an empty library and said
 * nothing, and the only recovery was a full logout and the whole OAuth flow again (cu-84).
 *
 * [PlexTokenAuthenticator] already knows: a 401 that survives its single server-token refresh means
 * the *account* token is bad. It logged that and gave up silently. This is where it records it, so
 * the UI can say so and offer re-authentication.
 *
 * **A network failure must never land here.** Being offline is not being signed out, and treating it
 * as such would nag every user on a train. Only an authenticated request that came back 401 counts.
 */
@Singleton
class AccountAuthState
  @Inject
  constructor() {
    private val _isSignedOut = MutableStateFlow(false)

    /** True once a 401 has survived re-auth. Cleared by [onAuthenticated]. */
    val isSignedOut: StateFlow<Boolean> = _isSignedOut

    /**
     * Records that the account token is no longer accepted.
     *
     * Called only from the authenticator's give-up path, never on a connection error.
     */
    fun onAccountRejected() {
      if (!_isSignedOut.value) {
        Timber.w("Account token rejected; signalling signed-out state")
      }
      _isSignedOut.value = true
    }

    /** Records a successful authenticated exchange, clearing any signed-out state. */
    fun onAuthenticated() {
      if (_isSignedOut.value) {
        Timber.i("Account accepted again; clearing signed-out state")
      }
      _isSignedOut.value = false
    }
  }
