package io.github.mattpvaughn.chronicle.data.sources.plex.model

import kotlinx.serialization.Serializable

@Serializable
data class OAuthResponse(
  val id: Long,
  val clientIdentifier: String,
  val code: String,
  val authToken: String? = null,
)
