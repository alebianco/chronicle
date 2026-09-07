package io.github.mattpvaughn.chronicle.data.sources.plex.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * A <Device/> type object from the Plex API. Can represent a Plex server, player, or remote, as
 * designated by the [provides] field
 */
@JsonClass(generateAdapter = true)
data class PlexServer(
  val name: String = "",
  val provides: String = "",
  val connections: List<Connection> = emptyList(),
  val clientIdentifier: String = "",
  val accessToken: String? = "",
  // assume owned server as this is probably more common
  val owned: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class Connection(
  val uri: String = "",
  val local: Boolean = false,
  /**
   * True when this route is proxied through Plex's relay.
   *
   * Reported by `/api/v2/resources` — which the app already queries with
   * `includeRelay = 1` — but previously discarded at parse time, so relay routes were
   * raced on equal footing with LAN despite being capped around 2 Mbps behind an extra
   * hop through Plex's infrastructure.
   */
  val relay: Boolean = false,
  /** "http" or "https". Informational, so a tier decision can be audited from a log. */
  val protocol: String = "",
  /**
   * True when this route's address is an IPv6 literal.
   *
   * **Parsed but deliberately not acted on**. `/api/v2/resources` reports it beside `local`
   * and `relay`, and it was dropped from use. Nothing prefers or avoids IPv6, because there is no failing
   * network to justify a rule: the household's server advertises **three connections, all
   * `"IPv6": false`, none an IPv6 literal** — checked against the live server, not a fixture, since
   * the fixture omits the key entirely.
   *
   * Carrying it makes the flag visible in a log when a connection problem *is* reported, which is
   * what a future decision would need. Adding a tier or a filter on today's evidence would be
   * guesswork, and a wrong guess degrades the networks that already work.
   *
   * `@Json` names it explicitly: the wire key is `IPv6` and Moshi is case-sensitive, so the
   * inferred `iPv6` would silently never match — the exact defect once found in `plexGenres`.
   */
  @Json(name = "IPv6")
  val iPv6: Boolean = false,
)

/**
 * How good a route to the server is, best first.
 *
 * Declaration order *is* the preference order: [ConnectionTier.entries] and [Comparable]
 * both rely on it, so do not reorder these without reading `ConnectionChooser`.
 */
enum class ConnectionTier { LAN, DIRECT, RELAY }

/**
 * [Connection.relay] is checked before [Connection.local] deliberately: Plex has been
 * observed to report a relay connection with `local = 1`, and treating that as LAN would
 * put the slowest available route first — the exact failure this tiering exists to avoid.
 */
val Connection.tier: ConnectionTier
  get() =
    when {
      relay -> ConnectionTier.RELAY
      local -> ConnectionTier.LAN
      else -> ConnectionTier.DIRECT
    }
