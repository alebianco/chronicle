package io.github.mattpvaughn.chronicle.data.sources.plex

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks plex.tv whether this install is still a registered device on the account.
 *
 * Exists because **removing a device at plex.tv invalidates no token**. Measured during the cu-73
 * live pass: after the owner deleted the device, the app made 111 requests and every one returned
 * `200`. There is no rejection to react to, so the only way to honour a revocation is to ask
 * (decision-17).
 *
 * ### The rule that keeps cu-84 true
 *
 * Only a **successful, parseable** answer that omits this client counts as revocation. Every other
 * outcome — offline, timeout, 5xx, malformed body — is [AccountAuthState.State.Unknown], because
 * failing to reach plex.tv says nothing about authorization. Getting this backwards would
 * reintroduce the exact bug cu-84 fixed: nagging a user who is merely on a train.
 */
@Singleton
class DeviceAuthorizationCheck
  @Inject
  constructor(
    private val plexLoginService: PlexLoginService,
    private val plexPrefsRepo: PlexPrefsRepo,
    private val accountAuthState: AccountAuthState,
  ) {
    /**
     * Runs the check and records the outcome on [AccountAuthState].
     *
     * Never throws: a failure here must not break app startup, and is not evidence of anything.
     */
    suspend fun run() {
      val uuid = plexPrefsRepo.uuid
      if (uuid.isEmpty()) {
        // No identity to check against yet — a first run, before login.
        accountAuthState.onCheckInconclusive("no client identifier stored")
        return
      }

      val devices =
        try {
          plexLoginService.devices()
        } catch (e: Exception) {
          // Includes offline, timeouts, 5xx and a 401. A 401 here *is* meaningful, but it is the
          // authenticator's business to interpret and it already records it; treating it as a
          // device revocation would mislabel the reason.
          Timber.i("Device check did not complete: ${e.javaClass.simpleName}")
          accountAuthState.onCheckInconclusive(e.javaClass.simpleName)
          return
        }

      if (devices.isEmpty()) {
        // A successful call cannot legitimately return zero devices while this one is making it.
        // Far likelier a shape change or a proxy, and concluding "revoked" from it would sign
        // everyone out at once.
        Timber.w("Device check returned an empty list; treating as inconclusive")
        accountAuthState.onCheckInconclusive("empty device list")
        return
      }

      val stillRegistered = devices.any { it.clientIdentifier == uuid }
      if (stillRegistered) {
        accountAuthState.onAuthenticated()
      } else {
        Timber.w("This client is not among the account's ${devices.size} devices; revoked")
        accountAuthState.onDeviceRevoked()
      }
    }
  }
