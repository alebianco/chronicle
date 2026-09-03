package io.github.mattpvaughn.chronicle.data.sources.plex.model

import com.squareup.moshi.JsonClass
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter

@JsonClass(generateAdapter = true)
data class PlexChapter(
  val id: Long = 0L,
  val filter: String = "",
  val tag: String = "",
  val index: Long = 0L,
  val discNumber: Int = 0,
  // These are Plex's JSON key names and Moshi maps by field name — they must NOT be renamed to
  // match `Chapter.bookStartTimeOffset` (cu-96). Renaming them silently stops chapters parsing:
  // every offset defaults to 0 and no test that mocks the API notices.
  val startTimeOffset: Long = 0L,
  val endTimeOffset: Long = 0L,
)

/**
 * Maps a Plex chapter onto the neutral [Chapter] entity.
 *
 * [bookId] is required rather than defaulted: chapters live in a shared table keyed partly on it
 * (cu-49), so an unset book id would collide with every other chapter in the library. It was
 * genuinely absent while chapters were serialized inside `Audiobook.chapters`, where the
 * containing book was implicit.
 */
fun PlexChapter.toChapter(
  trackId: String,
  trackDiscNumber: Int,
  downloaded: Boolean,
  bookId: String,
): Chapter {
  return Chapter(
    title = tag.takeIf { it.isNotEmpty() } ?: "Chapter $index",
    id = id.toString(),
    index = index,
    discNumber = discNumber.takeIf { it != 0 } ?: trackDiscNumber,
    bookStartTimeOffset = BookOffset(startTimeOffset),
    bookEndTimeOffset = BookOffset(endTimeOffset),
    downloaded = downloaded,
    trackId = trackId,
    bookId = bookId,
  )
}
