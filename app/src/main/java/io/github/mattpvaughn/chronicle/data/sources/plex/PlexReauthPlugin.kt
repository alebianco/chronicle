package io.github.mattpvaughn.chronicle.data.sources.plex

import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpStatusCode
import timber.log.Timber

/**
 * Recovers from a 401 by re-fetching the server access token and retrying the request **once**.
 *
 * The port of `PlexTokenAuthenticator`, and the one place in the Ktor migration where a guarantee
 * changed hands. That class was an OkHttp `Authenticator` rather than an `Interceptor` on purpose:
 * OkHttp invokes an `Authenticator` only on a 401 and threads the previous attempt through
 * `Response.priorResponse`, so *"retry exactly once"* was a property of the framework.
 *
 * Ktor has no equivalent. Its `Auth` plugin is built for `Authorization: Bearer` with a
 * refresh-token grant, and **Plex has neither** — a custom `X-Plex-Token` header, and no refresh
 * mechanism at all. So the retry lives on the [Send] hook, where `proceed` is called explicitly:
 * once for the original request, and at most once more after a successful refresh. There is no
 * loop and no counter, because the second `proceed` is the last statement on its path — the
 * once-only property is now *structural in this function* rather than inherited, and
 * `PlexReauthWiringTest` exists to fail if that ever stops being true.
 *
 * The recovery logic itself is unchanged, including the part that matters most — **only half of a
 * Plex 401 is recoverable**:
 *
 * - An **account** token can only be replaced by a human approving an OAuth PIN in a browser.
 *   Nothing can be done in the background.
 * - A **server** access token can be re-fetched from `/api/v2/resources` using the account token,
 *   which covers the case that actually happens: a server re-claimed, or its token rotated, leaving
 *   a stale value cached while the account is still valid.
 *
 * Plex tokens do not expire on a timer — they are invalidated by an event, such as a password
 * change with "sign out connected devices". So a 401 that survives the retry means the account
 * itself is signed out, and the only honest response is to keep playing from cache and tell the
 * user.
 *
 * @param refreshServer re-fetches the server from `/api/v2/resources`, or returns null if that is
 *   not possible right now. A lambda so this can be tested without standing up Ktorfit.
 */
fun plexReauthPlugin(
  plexPrefsRepo: PlexPrefsRepo,
  // No default: an accidentally private instance would record a signed-out state nothing
  // observes, which is indistinguishable from the bug this fixes.
  accountAuthState: AccountAuthState,
  refreshServer: suspend () -> ServerModel?,
) = createClientPlugin("PlexReauth") {
  on(Send) { request ->
    val originalCall = proceed(request)
    if (originalCall.response.status != HttpStatusCode.Unauthorized) {
      return@on originalCall
    }

    val staleToken = plexPrefsRepo.server?.accessToken

    val refreshed =
      runCatching { refreshServer() }
        .onFailure { Timber.w(it, "Server refresh threw while handling a 401") }
        .getOrNull()

    if (refreshed == null) {
      // Deliberately *not* a signed-out signal. The refresh can fail because the network is
      // gone, and being offline is not being signed out — claiming otherwise would nag every
      // user on a train.
      Timber.w("Could not refresh the server token after a 401")
      return@on originalCall
    }

    if (refreshed.accessToken.isEmpty() || refreshed.accessToken == staleToken) {
      // Plex handed back the same token (or none), so the rejection is not about the server
      // token. The account is signed out and only the user can fix that.
      Timber.w("Server token unchanged after a 401; the account token is likely invalid")
      // The definitive case: plex.tv answered, and still refuses. Recorded so the UI can say so
      // and offer re-authentication rather than presenting stale data silently.
      accountAuthState.onAccountRejected()
      return@on originalCall
    }

    plexPrefsRepo.server = refreshed
    // A fresh token means the account is still good, so clear any earlier signed-out state.
    accountAuthState.onAuthenticated()
    Timber.i("Refreshed the server token after a 401; retrying once")

    request.headers.remove("X-Plex-Token")
    request.headers.append("X-Plex-Token", refreshed.accessToken)

    // The retry, and the end of the road. Whatever this returns is the caller's answer — a
    // second 401 included — because there is no path from here back to another `proceed`.
    proceed(request)
  }
}
