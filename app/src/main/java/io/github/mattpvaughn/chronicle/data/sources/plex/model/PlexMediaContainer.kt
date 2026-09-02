package io.github.mattpvaughn.chronicle.data.sources.plex.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.data.model.MediaId
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack

@JsonClass(generateAdapter = true)
data class PlexMediaContainerWrapper(
  @Json(name = "MediaContainer") val plexMediaContainer: PlexMediaContainer,
)

@JsonClass(generateAdapter = true)
data class PlexMediaContainer(
  val playQueueSelectedItemID: Long = -1,
  @Json(name = "Directory")
  val plexDirectories: List<PlexDirectory> = emptyList(),
  @Json(name = "Metadata")
  val metadata: List<PlexDirectory> = emptyList(),
  val mediaProvider: MediaProvider? = null,
  val devices: List<PlexServer> = emptyList(),
  val size: Long = 0,
  val totalSize: Long = 0,
  val offset: Long = 0,
)

@JsonClass(generateAdapter = true)
data class PlexGenre(val tag: String = "")

/**
 * Where a server response becomes local models — and therefore the one place to reject an id that
 * is unsafe to use as a filename (cu-111).
 *
 * Every fetch funnels through these three, so validating here covers the whole surface rather than
 * relying on each call site to remember. An item with an unsafe id is **dropped**, not repaired:
 * a rewritten id would not match the server's on any later request, so the book would be
 * permanently broken in a subtler way. Dropping one item and logging it leaves the rest of the
 * library working, which matters because the only way to reach this is a compromised or
 * misbehaving server.
 */
fun PlexMediaContainer.asAudiobooks(): List<Audiobook> {
  return metadata
    .filter { MediaId.isValidOrLog(it.ratingKey, "book '${it.title}'") }
    .map { Audiobook.from(it) }
}

fun PlexMediaContainer.asTrackList(): List<MediaItemTrack> {
  return metadata
    .filter { MediaId.isValidOrLog(it.ratingKey, "track '${it.title}'") }
    .asMediaItemTracks()
}

fun PlexMediaContainer.asCollections(): List<Collection> {
  return metadata
    .filter { MediaId.isValidOrLog(it.ratingKey, "collection '${it.title}'") }
    .map { Collection.from(it) }
}
