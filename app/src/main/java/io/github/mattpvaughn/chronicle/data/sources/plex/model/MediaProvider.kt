package io.github.mattpvaughn.chronicle.data.sources.plex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MediaProvider(
  @SerialName("Feature") val feature: List<Feature> = emptyList(),
)
