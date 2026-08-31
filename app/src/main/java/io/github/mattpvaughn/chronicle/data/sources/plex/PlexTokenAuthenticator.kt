package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.model.ServerModel
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import timber.log.Timber

/**
 * Recovers from a 401 by re-fetching the server access token and retrying the request once.
 *
 * Plex has no refresh-token mechanism, so only half of this is recoverable:
 *
 * - An **account** token can only be replaced by a human approving an OAuth PIN in a
 *   browser. Nothing can be done in the background.
 * - A **server** access token can be re-fetched from `/api/v2/resources` using the account
 *   token, which covers the case that actually happens: a server re-claimed, or its token
 *   rotated, leaving a stale value cached while the account is still valid.
 *
 * Plex tokens do not expire on a timer — they are invalidated by an event, such as a
 * password change with "sign out connected devices". So a 401 that survives the retry below
 * means the account itself is signed out, and the only honest response is to keep playing
 * from cache and tell the user (see `playback_error_signed_out`).
 *
 * An [Authenticator] rather than an [okhttp3.Interceptor] because OkHttp invokes it only on
 * a 401 and threads the previous attempt through [Response.priorResponse] — which is what
 * makes "retry exactly once" a property of the framework rather than hand-rolled state.
 *
 * @param refreshServer re-fetches the server from `/api/v2/resources`, or returns null if
 *   that is not possible right now. Injected as a lambda so this class can be tested
 *   without standing up Retrofit.
 */
class PlexTokenAuthenticator(
  private val plexPrefsRepo: PlexPrefsRepo,
  private val refreshServer: suspend () -> ServerModel?,
) : Authenticator {
  override fun authenticate(
    route: Route?,
    response: Response,
  ): Request? {
    // Already retried once. Either the refreshed token is bad too or the account token is
    // itself invalid; trying again would loop against plex.tv.
    if (response.priorResponse != null) {
      Timber.w("Re-auth already attempted for ${response.request.url}; giving up")
      return null
    }

    val staleToken = plexPrefsRepo.server?.accessToken

    // runBlocking is correct here: authenticate() is a blocking callback invoked on
    // OkHttp's own connection thread, never the main thread.
    val refreshed =
      runBlocking {
        runCatching { refreshServer() }
          .onFailure { Timber.w(it, "Server refresh threw while handling a 401") }
          .getOrNull()
      }

    if (refreshed == null) {
      Timber.w("Could not refresh the server token after a 401")
      return null
    }

    if (refreshed.accessToken.isEmpty() || refreshed.accessToken == staleToken) {
      // Plex handed back the same token (or none), so the rejection is not about the
      // server token. The account is signed out and only the user can fix that.
      Timber.w("Server token unchanged after a 401; the account token is likely invalid")
      return null
    }

    plexPrefsRepo.server = refreshed
    Timber.i("Refreshed the server token after a 401; retrying once")
    return response.request.newBuilder()
      .header("X-Plex-Token", refreshed.accessToken)
      .build()
  }
}
