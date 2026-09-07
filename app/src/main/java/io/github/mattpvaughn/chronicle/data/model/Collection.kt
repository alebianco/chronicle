package io.github.mattpvaughn.chronicle.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexDirectory
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import timber.log.Timber

@TypeConverters(CollectionIdConverter::class, SourceIdConverters::class)
@Entity
data class Collection(
  @PrimaryKey
  val id: String,
  /** Which backend installation this collection came from — see [SourceId], decision-21. */
  val source: SourceId,
  val title: String,
  val childCount: Long = 0L,
  val sortType: SortType = SortType.RELEASE_DATE,
  val isCached: Boolean = false,
  val thumb: String = "",
  val childIds: List<String> = emptyList(),
) {
  companion object {
    fun from(dir: PlexDirectory) =
      Collection(
        id = dir.ratingKey,
        source = SourceId.UNKNOWN,
        title = dir.title,
        childCount = dir.childCount,
        sortType = SortType.fromPlexCode(dir.collectionSort.toInt()),
        thumb = dir.thumb,
      )

    val PLEX_COLLECTION_SORT_TYPE_RELEASE_DATE = 0
    val PLEX_COLLECTION_SORT_TYPE_ALPHABETICAL = 1
    val PLEX_COLLECTION_SORT_TYPE_CUSTOM = 2
  }

  enum class SortType {
    RELEASE_DATE,
    ALPHABETICAL,
    CUSTOM,
    ;

    companion object {
      fun fromPlexCode(plexCode: Int?): SortType {
        return when (plexCode) {
          PLEX_COLLECTION_SORT_TYPE_RELEASE_DATE -> RELEASE_DATE
          PLEX_COLLECTION_SORT_TYPE_ALPHABETICAL -> ALPHABETICAL
          PLEX_COLLECTION_SORT_TYPE_CUSTOM -> CUSTOM
          else -> RELEASE_DATE
        }
      }
    }
  }
}

/**
 * Serializes a collection's child ids to the stored JSON array.
 *
 * Room instantiates a `@TypeConverters(::class)` converter reflectively, so this cannot take a
 * dependency by construction without moving to `addTypeConverter` plumbing on every database that
 * uses it. It does not need to: it names a **serializer directly** rather than reaching into the DI
 * graph, which is what made the model unconstructable in a test without standing up
 * `ChronicleApplication`.
 *
 * `ListSerializer(String.serializer())` rather than the app's shared `Json`, on purpose: this is a
 * **database column format**, not a wire or file format, and it must not inherit a configuration
 * change made for one of those. `Json.Default` writes a `List<String>` as a plain JSON array —
 * identical bytes to what the previous Moshi adapter wrote, so every already-stored row still
 * reads. `CollectionIdConverterTest` pins that stored form so a change cannot pass unnoticed.
 */
class CollectionIdConverter {
  private val stringsSerializer = ListSerializer(String.serializer())

  // The stored form is unchanged: this always serialized a JSON array of strings
  // and only converted to Long in Kotlin. Dropping that conversion removes a lossy step —
  // `toLong()` would throw on a non-numeric child id.
  @TypeConverter
  fun fromList(value: List<String>): String {
    return Json.encodeToString(stringsSerializer, value)
  }

  /**
   * Reads the stored array back, treating an unreadable column as no children.
   *
   * The `catch` is not defensive padding: Moshi's `fromJson` returned `null` for a malformed value
   * and the old code mapped that to an empty list, whereas kotlinx **throws**. Without this, a row
   * corrupted by a hand-edited database would take down every query that touches the collection
   * table rather than costing one collection its child list.
   */
  @TypeConverter
  fun toList(value: String): List<String> {
    if (value.isEmpty()) {
      return emptyList()
    }
    return try {
      Json.decodeFromString(stringsSerializer, value)
    } catch (e: SerializationException) {
      Timber.w(e, "Unreadable collection childIds column; treating it as empty")
      emptyList()
    }
  }
}
