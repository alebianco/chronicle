package io.github.mattpvaughn.chronicle.data.sources.plex.model

import com.squareup.moshi.JsonClass

/**
 * One client registered against the Plex account, from `GET /api/v2/devices`.
 *
 * Deliberately **minimal**: only the fields the authorization check reads. The real response
 * carries ~19 keys per entry including `connections`, `syncLists`, `screenResolution` and a
 * per-device `token`, and modelling those would be a maintenance burden for data nothing uses —
 * and one of them is a *credential* this app has no reason to hold.
 *
 * A dedicated model rather than reusing `PlexServer`: that was tried first and failed with a
 * `JsonDataException` at runtime, because `/api/v2/devices` and `/api/v2/resources` differ in
 * shape (`id` is a number here, and `connections` differs). The failure was safe — the check
 * treats any exception as inconclusive — but it meant the check could never succeed.
 */
@JsonClass(generateAdapter = true)
data class PlexDevice(
  /** This app's `X-Plex-Client-Identifier`, which is what the check matches on. */
  val clientIdentifier: String = "",
  /** Display name, for logging only — never matched on, since logins share names. */
  val name: String = "",
  /** e.g. "Chronicle", "Plex Media Server". Logged for context. */
  val product: String = "",
)
