package io.github.mattpvaughn.chronicle.data.sources.plex.model

import io.github.mattpvaughn.chronicle.data.model.Collection.Companion.PLEX_COLLECTION_SORT_TYPE_RELEASE_DATE
import io.github.mattpvaughn.chronicle.data.model.PlexLibrary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Used to represent a <Directory/> element, typically contained by a [PlexMediaContainer]. It
 * represents some type of container for audio tracks/chapters. This could be of type
 * [MediaType.ALBUM], [MediaType.ARTIST], or [MediaType.PERSON] within the context of audio.
 */
@Serializable
data class PlexDirectory(
  val key: String = "",
  val title: String = "",
  val titleSort: String = "",
  val ratingKey: String = "",
  val parentRatingKey: Int = 0,
  val parentTitle: String = "",
  val type: String = "",
  val grandparentTitle: String = "",
  val thumb: String = "",
  val size: Int = 0,
  val summary: String = "",
  val parentYear: Int = 0,
  val year: Int = 0,
  val addedAt: Long = 0,
  val updatedAt: Long = 0,
  val viewedLeafCount: Long = 0,
  val leafCount: Long = 0,
  val lastViewedAt: Long = 0,
  val viewCount: Long = 0,
  /**
   * Plex sends this as `Genre`.
   *
   * The name annotation was **missing**, so the serializer looked for a key literally called
   * `plexGenres` and the field was always empty against a real server. Every test passed, because
   * the hand-written fixtures were written to match the code rather than the wire — which is
   * precisely why the facet tests below are pinned against the *captured* fixtures instead.
   */
  @SerialName("Genre")
  val plexGenres: List<PlexGenre> = emptyList(),
  /**
   * The narrator, by the Audnexus/seanap tagging convention.
   *
   * Plex's music schema has no narrator field, so the community convention puts it in `Style`.
   * **Never treat this as music semantics** — a "style" here is a person's name.
   *
   * Only present on the per-book detail response (`/library/metadata/{id}`), not on the library
   * listing: verified against fixtures captured from a real Plex 1.43.3 server.
   */
  @SerialName("Style")
  val plexStyles: List<PlexTag> = emptyList(),
  /**
   * The series, by the same convention: `Mood` tags, usually as `Series: <name>`.
   *
   * The prefix is stripped on the way in — see `seriesName`. Like [plexStyles], detail-only.
   */
  @SerialName("Mood")
  val plexMoods: List<PlexTag> = emptyList(),
  @SerialName("Chapter")
  val plexChapters: List<PlexChapter> = emptyList(),
  val duration: Long = 0L,
  val index: Int = 0,
  val parentIndex: Int = 1,
  @SerialName("Media")
  val media: List<Media> = emptyList(),
  val viewOffset: Long = 0L,
  @SerialName("Collection")
  val collections: List<CollectionWrapper>? = null,
  val childCount: Long = 0L,
  val collectionSort: String = PLEX_COLLECTION_SORT_TYPE_RELEASE_DATE.toString(),
)

@Serializable
data class CollectionWrapper(val tag: String? = null)

fun PlexDirectory.asLibrary(): PlexLibrary {
  return PlexLibrary(
    name = title,
    type =
      MediaType.TYPES.find { mediaType -> mediaType.typeString == this.type }
        ?: MediaType.ARTIST,
    id = key,
  )
}
