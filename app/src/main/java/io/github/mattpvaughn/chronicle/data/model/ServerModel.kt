package io.github.mattpvaughn.chronicle.data.model

import io.github.mattpvaughn.chronicle.data.sources.plex.model.Connection
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexServer

data class ServerModel(
  val name: String,
  val connections: List<Connection>,
  val serverId: String,
  // Access token for the server, needed for accessing shared servers
  val accessToken: String = "",
  val owned: Boolean = true,
)

/**
 * Folds a `/api/v2/resources` refresh into the cached server.
 *
 * A pure function because the launch path that used to do this inline discarded the
 * refreshed access token — it kept only `connections` — so a rotated server token was
 * fetched and thrown away on every launch (cu-10). Keeping the decision here makes it
 * testable without standing up an `Application`.
 *
 * @param fetched null when the refresh failed or timed out. The cached server is then
 *   returned unchanged: an offline launch must not degrade working credentials.
 */
fun mergeServerRefresh(
  cached: ServerModel,
  fetched: ServerModel?,
): ServerModel {
  if (fetched == null) return cached
  return cached.copy(
    // Freshest first, deduped. Connections are raced in order by
    // PlexConfig.chooseViableConnections, so a just-reported address is likelier to
    // answer; cached ones are kept because /resources can omit a LAN address the device
    // can still reach.
    connections = (fetched.connections + cached.connections).distinct(),
    // A shared server may legitimately report no token, so an empty value must never
    // overwrite one that works.
    accessToken = fetched.accessToken.ifEmpty { cached.accessToken },
  )
}

fun PlexServer.asServer(): ServerModel {
  return ServerModel(
    name = this.name,
    connections = this.connections,
    serverId = this.clientIdentifier,
    accessToken = this.accessToken ?: "",
    owned = this.owned,
  )
}
