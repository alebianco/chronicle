package io.github.mattpvaughn.chronicle.data.sources.plex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Feature(
  @SerialName("Directory") val plexDirectories: List<PlexDirectory> = emptyList(),
)
