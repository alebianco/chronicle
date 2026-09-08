package io.github.mattpvaughn.chronicle.data.sources.plex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlexUser(
  val id: Long = 0L,
  val uuid: String = "",
  val title: String = "",
  val username: String? = "",
  val thumb: String = "",
  // PIN REQUIRED IF TRUE
  val hasPassword: Boolean = true,
  val admin: Boolean = false,
  val guest: Boolean = false,
  val authToken: String? = "",
)

@Serializable
data class UsersResponse(
  @SerialName("users") val users: List<PlexUser>,
)
