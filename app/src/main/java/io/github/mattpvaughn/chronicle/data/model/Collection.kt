package io.github.mattpvaughn.chronicle.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.squareup.moshi.Types
import io.github.mattpvaughn.chronicle.application.Injector
import io.github.mattpvaughn.chronicle.data.sources.MediaSource
import io.github.mattpvaughn.chronicle.data.sources.SourceManager
import io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaSource
import io.github.mattpvaughn.chronicle.data.sources.plex.model.PlexDirectory

@TypeConverters(CollectionIdConverter::class)
@Entity
data class Collection(
  @PrimaryKey
  val id: String,
  /** Unique long representing a [MediaSource] in [SourceManager] */
  val source: Long,
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
        source = PlexMediaSource.MEDIA_SOURCE_ID_PLEX,
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

class CollectionIdConverter {
  private val stringType = Types.newParameterizedType(List::class.java, String::class.java)
  private val stringsAdapter = Injector.get().moshi().adapter<List<String>>(stringType)

  // The stored form is unchanged by cu-71: this always serialized a JSON array of strings
  // and only converted to Long in Kotlin. Dropping that conversion removes a lossy step —
  // `toLong()` would throw on a non-numeric child id.
  @TypeConverter
  fun fromList(value: List<String>): String {
    return stringsAdapter.toJson(value)
  }

  @TypeConverter
  fun toList(value: String): List<String> {
    if (value.isEmpty()) {
      return emptyList()
    }
    return stringsAdapter.fromJson(value) ?: emptyList()
  }
}
