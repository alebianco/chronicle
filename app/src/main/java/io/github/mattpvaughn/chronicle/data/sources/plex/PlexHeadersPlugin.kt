package io.github.mattpvaughn.chronicle.data.sources.plex

import android.os.Build
import io.github.mattpvaughn.chronicle.BuildConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom

/**
 * The Plex identity headers, and the base-URL substitution, for Ktor.
 *
 * The port of `PlexInterceptor`. Every header it set is set here, with one deliberate difference in
 * shape: the token is resolved by a lambda rather than read from prefs inline, so the login and
 * media variants are the same plugin with different token sources instead of one class carrying an
 * `isLoginService` boolean.
 *
 * **The URL substitution is load-bearing.** Retrofit endpoints are declared against
 * [PLACEHOLDER_URL] and rewritten per request to whichever Plex connection was chosen, because the
 * server address is only known at runtime and can change mid-session when connectivity shifts.
 * That behaviour is unchanged — a request whose host is the placeholder gets the current
 * `plexConfig.url`.
 *
 * @param authToken the token to send, or an empty string to send none. Empty counts as absent
 *   rather than as a header with no value: an empty `X-Plex-Token` is a *different* request to one
 *   with no token at all, and Plex answers them differently.
 */
fun plexHeadersPlugin(
  plexPrefsRepo: PlexPrefsRepo,
  plexConfig: PlexConfig,
  authToken: () -> String,
) = createClientPlugin("PlexHeaders") {
  onRequest { request, _ ->
    request.substitutePlaceholderHost(plexConfig)

    request.headers.apply {
      set("Accept", "application/json")
      set("X-Plex-Platform", PlexInterceptor.PLATFORM)
      set("X-Plex-Provides", "player")
      set("X-Plex-Client-Identifier", plexPrefsRepo.uuid)
      set("X-Plex-Version", BuildConfig.VERSION_NAME)
      set("X-Plex-Product", PlexInterceptor.PRODUCT)
      set("X-Plex-Platform-Version", Build.VERSION.RELEASE)
      set("X-Plex-Session-Identifier", plexConfig.sessionIdentifier)
      set("X-Plex-Client-Name", APP_NAME)
      set("X-Plex-Device", PlexInterceptor.DEVICE)
      set("X-Plex-Device-Name", Build.MODEL)
    }

    val token = authToken()
    if (token.isNotEmpty()) {
      request.headers.set("X-Plex-Token", token)
    }
  }
}

/**
 * Rewrites a request built against [PLACEHOLDER_URL] to the currently chosen Plex connection.
 *
 * A no-op for any other host, so an absolute URL — a `plex.tv` login call, or a download URL
 * already resolved by `toServerString` — passes through untouched.
 */
internal fun HttpRequestBuilder.substitutePlaceholderHost(plexConfig: PlexConfig) {
  val current = url.buildString()
  if (!current.contains(PLACEHOLDER_URL)) return
  url.takeFrom(URLBuilder(current.replace(PLACEHOLDER_URL, plexConfig.url)))
}
