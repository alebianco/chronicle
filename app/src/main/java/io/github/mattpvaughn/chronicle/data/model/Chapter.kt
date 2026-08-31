package io.github.mattpvaughn.chronicle.data.model

import android.text.format.DateUtils
import androidx.room.Entity
import androidx.room.TypeConverter
import io.github.mattpvaughn.chronicle.data.local.ITrackRepository.Companion.TRACK_NOT_FOUND
import timber.log.Timber

/**
 * Keyed on `(bookId, trackId, discNumber, index)` rather than on [id].
 *
 * [id] is the value the server gave, and it is **not safe as a primary key** (cu-49). It arrives
 * from two different namespaces: `PlexChapter.id` on the Plex path, and the *track* id on the
 * per-track fallback (`MediaItemTrack.asChapter`). Plex hands out chapter and track ratingKeys
 * from one server-wide sequence, so the two can collide, and `insertAll` uses
 * `OnConflictStrategy.REPLACE` — a collision would silently drop one book's chapter in favour of
 * another's. That was harmless while chapters were serialized inside `Audiobook.chapters`, where
 * the containing book was implicit and each list was stored separately.
 *
 * The composite key is unique without trusting either namespace: one book, one track, one disc,
 * one chapter index. [id] stays as a plain column — it is still what the server said, and
 * `updateCachedStatus` addresses rows by it.
 */
@Entity(primaryKeys = ["bookId", "trackId", "discNumber", "index"])
data class Chapter(
  val title: String = "",
  val id: String = "0",
  val index: Long = 0L,
  val discNumber: Int = 1,
  // The number of milliseconds from the start of the containing track and the start of the chapter
  val startTimeOffset: Long = 0L,
  // The number of milliseconds between the start of the containing track and the end of the chapter
  val endTimeOffset: Long = 0L,
  val downloaded: Boolean = false,
  val trackId: String = TRACK_NOT_FOUND,
  val bookId: String = NO_AUDIOBOOK_FOUND_ID,
) : Comparable<Chapter> {
  val durationStr: String
    get() =
      DateUtils.formatElapsedTime(
        StringBuilder(),
        (endTimeOffset - startTimeOffset) / 1000,
      )

  /** A string representing the index but padded to [length] characters with zeroes */
  fun paddedIndex(length: Int): String {
    return index.toString().padStart(length, '0')
  }

  override fun compareTo(other: Chapter): Int {
    val discCompare = discNumber.compareTo(other.discNumber)
    if (discCompare != 0) {
      return discCompare
    }
    return index.compareTo(other.index)
  }
}

val EMPTY_CHAPTER = Chapter("")

/**
 * Returns the chapter which contains the [timeStamp] (the playback progress of the track containing
 * this chapter), or [EMPTY_TRACK] if there is no chapter
 */
fun List<Chapter>.getChapterAt(
  trackId: String,
  timeStamp: Long,
): Chapter {
  for (chapter in this) {
    if (chapter.trackId == trackId && timeStamp in chapter.startTimeOffset..chapter.endTimeOffset) {
      return chapter
    }
  }
  return EMPTY_CHAPTER
}

/**
 * Persists a chapter list into a single Room column.
 *
 * The format joins fields with [FIELD_SEPARATOR] and records with [RECORD_SEPARATOR]. Those
 * characters are **escaped** in titles, because a title containing one used to shift or split the
 * record and make the decoder throw — `IndexOutOfBoundsException` for a stray record separator,
 * `NumberFormatException` for a field separator. Room raises that while reading the row, so the
 * book crashed on open, permanently, until the row was deleted. Plex titles are arbitrary
 * server-side strings and a rights line like "© 2019 Macmillan Audio" is ordinary chapter
 * metadata, so it was reachable (cu-78).
 *
 * Escaping is backward compatible: rows written before it decode unchanged, because only titles
 * that would have broken contain anything to unescape.
 */
class ChapterListConverter {
  @TypeConverter
  fun toChapterList(s: String): List<Chapter> {
    if (s.isEmpty()) {
      return emptyList()
    }
    // A malformed record is skipped rather than propagated: one bad chapter must not make a
    // whole book unopenable, which is what throwing out of a type converter does.
    return s.split(RECORD_SEPARATOR).mapNotNull { record ->
      try {
        decodeChapter(record)
      } catch (e: Exception) {
        Timber.e(e, "Skipping malformed chapter record")
        null
      }
    }
  }

  private fun decodeChapter(record: String): Chapter {
    val split = record.split(FIELD_SEPARATOR)
    val discNumber = if (split.size >= 6) split[5].toInt() else 1
    val downloaded = if (split.size >= 7) split[6].toBoolean() else false
    val trackId = if (split.size >= 8) split[7] else TRACK_NOT_FOUND
    val bookId = if (split.size >= 9) split[8] else NO_AUDIOBOOK_FOUND_ID
    return Chapter(
      title = split[0].unescapeSeparators(),
      id = split[1],
      index = split[2].toLong(),
      startTimeOffset = split[3].toLong(),
      endTimeOffset = split[4].toLong(),
      discNumber = discNumber,
      downloaded = downloaded,
      trackId = trackId,
      bookId = bookId,
    )
  }

  @TypeConverter
  fun toString(chapters: List<Chapter>): String =
    chapters.joinToString(RECORD_SEPARATOR) {
      listOf(
        it.title.escapeSeparators(),
        it.id,
        it.index,
        it.startTimeOffset,
        it.endTimeOffset,
        it.discNumber,
        it.downloaded,
        it.trackId,
        it.bookId,
      ).joinToString(FIELD_SEPARATOR)
    }

  private companion object {
    const val FIELD_SEPARATOR = "\u00A9"
    const val RECORD_SEPARATOR = "\u00AE"
    const val ESCAPE = "\u241B"

    /** Escape the escape first, or unescaping would be ambiguous. */
    fun String.escapeSeparators(): String =
      replace(ESCAPE, "$ESCAPE$ESCAPE")
        .replace(FIELD_SEPARATOR, "${ESCAPE}F")
        .replace(RECORD_SEPARATOR, "${ESCAPE}R")

    fun String.unescapeSeparators(): String {
      val out = StringBuilder(length)
      var i = 0
      while (i < length) {
        val c = this[i]
        if (c.toString() == ESCAPE && i + 1 < length) {
          val escaped =
            when (this[i + 1]) {
              'F' -> FIELD_SEPARATOR
              'R' -> RECORD_SEPARATOR
              else -> this[i + 1].toString()
            }
          out.append(escaped)
          i += 2
        } else {
          out.append(c)
          i++
        }
      }
      return out.toString()
    }
  }
}
