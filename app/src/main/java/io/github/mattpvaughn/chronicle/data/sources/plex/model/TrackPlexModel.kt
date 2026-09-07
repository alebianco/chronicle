package io.github.mattpvaughn.chronicle.data.sources.plex.model

import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A model for the "Media" element of a "Track" entity. Only requires a "Part" for our uses */
@Serializable
data class Media(
  @SerialName("Part") val part: List<Part> = emptyList(),
)

/** A model for the "Part" element of a "Media" entity. Only need the key for our uses */
@Serializable
data class Part(val key: String = "", val size: Long = 0)

fun List<PlexDirectory>?.asMediaItemTracks(): List<MediaItemTrack> {
  // Rewrite indices to reflect their order in audiobook, ignoring numbers passed from server
  return this?.map { song ->
    MediaItemTrack.fromPlexModel(networkTrack = song)
  } ?: emptyList()
}

/**
 * @return the total duration of all the tracks
 */
fun List<MediaItemTrack>.getDuration(): Long {
  return this.asSequence().map(MediaItemTrack::duration).sum()
}
