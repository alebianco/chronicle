package io.github.mattpvaughn.chronicle.data.sources.plex.model

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
   * hop through Plex's infrastructure (cu-11).
   */
  val relay: Boolean = false,
  /** "http" or "https". Informational, so a tier decision can be audited from a log. */
  val protocol: String = "",
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
