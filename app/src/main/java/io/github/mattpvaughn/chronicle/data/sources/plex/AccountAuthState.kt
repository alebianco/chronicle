package io.github.mattpvaughn.chronicle.data.sources.plex

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the Plex account still accepts this app, as observed from actual server answers.
 *
 * `PlexLoginRepo.determineLoginState` decides from **presence** — a non-empty stored token means
 * `LOGGED_IN_FULLY`. Plex tokens never expire on a timer; they are invalidated by an event (a
 * password change with "sign out connected devices", a server re-claim, the device being removed
 * from plex.tv), so a stored token can be dead while looking perfectly valid.
 *
 * ### Why three states and not a boolean (decision-17)
 *
 * A boolean forces "not known to be signed out" and "known to be fine" into one value, and the
 * app then has to guess which it meant. The live pass showed both failure directions:
 *
 * - a real 401 from plex.tv was **swallowed** as though it were an offline launch, so the user was
 *   never told (DRAFT-123);
 * - a device removed at plex.tv kept working entirely, because Plex invalidates no token and there
 *   is nothing to react to (DRAFT-122).
 *
 * [Unknown] is therefore a first-class answer: it is what "the network did not tell us" means, and
 * it must behave exactly like [Authenticated] as far as the user is concerned. Only a *successful*
 * negative answer moves the state to [Revoked].
 *
 * **A network failure must never land in [Revoked].** Being offline is not being signed out, and
 * treating it as such would nag every user on a train (cu-84). A timeout, a connection error, a
 * 5xx or an unparseable body all mean [Unknown].
 */
@Singleton
class AccountAuthState
  @Inject
  constructor() {
    /** What the server last told us about this app's authorization. */
    enum class State {
      /** A request was accepted. */
      Authenticated,

      /** Nothing recent to go on — offline, not yet tried, or the check failed. Treat as fine. */
      Unknown,

      /** The server explicitly refused, or explicitly no longer lists this client. */
      Revoked,
    }

    private val _state = MutableStateFlow(State.Unknown)

    /** The current account state. */
    val state: StateFlow<State> = _state

    /**
     * True only when the account is definitively [State.Revoked].
     *
     * Deliberately *not* true for [State.Unknown]: callers asking this question are deciding
     * whether to tell the user something is wrong, and "we could not check" is not grounds.
     *
     * A plain value rather than a flow — the one caller (`PlexLoginRepo.determineLoginState`)
     * reads it synchronously while deciding a login state. Observers should collect [state].
     */
    val isRevoked: Boolean
      get() = _state.value == State.Revoked

    /**
     * Records that the account token is no longer accepted — a 401 that survived the
     * authenticator's single server-token refresh.
     */
    fun onAccountRejected() {
      if (_state.value != State.Revoked) {
        Timber.w("Account token rejected; signalling revoked state")
      }
      _state.value = State.Revoked
    }

    /**
     * Records that plex.tv answered successfully and did **not** list this client any more, i.e.
     * the user removed the device.
     *
     * Separate entry point from [onAccountRejected] so the *reason* is visible in logs; both land
     * in the same state, because both mean "the user must sign in again".
     */
    fun onDeviceRevoked() {
      if (_state.value != State.Revoked) {
        Timber.w("This device is no longer listed on the account; signalling revoked state")
      }
      _state.value = State.Revoked
    }

    /** Records a successful authenticated exchange, clearing any revoked state. */
    fun onAuthenticated() {
      if (_state.value == State.Revoked) {
        Timber.i("Account accepted again; clearing revoked state")
      }
      _state.value = State.Authenticated
    }

    /**
     * Records that the check could not be made — offline, timed out, or the answer was unusable.
     *
     * Never downgrades a known-good [State.Authenticated]: failing to reach the server tells us
     * nothing new, and flapping the state on every lost connection would make it useless.
     */
    fun onCheckInconclusive(reason: String) {
      if (_state.value == State.Unknown) {
        return
      }
      if (_state.value == State.Revoked) {
        // A failed check is not evidence the revocation was lifted.
        Timber.i("Account check inconclusive ($reason); staying revoked")
        return
      }
      Timber.i("Account check inconclusive ($reason); state unchanged")
    }
  }
