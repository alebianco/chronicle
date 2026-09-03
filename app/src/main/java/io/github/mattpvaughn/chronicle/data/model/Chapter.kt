package io.github.mattpvaughn.chronicle.data.model

import android.text.format.DateUtils
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.TypeConverter
import androidx.room.TypeConverters
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
@TypeConverters(OffsetConverters::class)
@Entity(primaryKeys = ["bookId", "trackId", "discNumber", "index"])
data class Chapter(
  val title: String = "",
  val id: String = "0",
  val index: Long = 0L,
  val discNumber: Int = 1,
  /**
   * Milliseconds from the start of the **book** — not the containing track (cu-96).
   *
   * The old name, `bookStartTimeOffset`, reads as "offset from the start of *something*", and the
   * something has now been guessed wrong four separate times: cu-13, cu-49, cu-93's display half,
   * and `PlayerExt.skipToPrevious`/`skipToNext`, which subtracted it from an in-track position and
   * handed it to `seekTo` as an in-track offset. The comment here used to say "from the start of
   * the containing track", which was simply wrong and is presumably where the confusion started.
   *
   * On a single-file book the two frames coincide, which is why the arithmetic worked by accident
   * on the owner's library and only misbehaves on a genuinely multi-track book.
   *
   * The **column** keeps its old name via [ColumnInfo]: renaming it would need a `ChapterDatabase`
   * migration and a change to the `Audiobook.chapters` serialization format, for no behavioural
   * gain, while cu-82 is already scheduled to retire that dual write.
   */
  @ColumnInfo(name = "startTimeOffset")
  val bookStartTimeOffset: BookOffset = BookOffset.ZERO,
  /** Milliseconds from the start of the **book** to the end of the chapter. See [bookStartTimeOffset]. */
  @ColumnInfo(name = "endTimeOffset")
  val bookEndTimeOffset: BookOffset = BookOffset.ZERO,
  val downloaded: Boolean = false,
  val trackId: String = TRACK_NOT_FOUND,
  val bookId: String = NO_AUDIOBOOK_FOUND_ID,
) : Comparable<Chapter> {
  val durationStr: String
    get() =
      DateUtils.formatElapsedTime(
        StringBuilder(),
        (bookEndTimeOffset - bookStartTimeOffset) / 1000,
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
 * The chapter on track [trackId] containing [bookPosition], or [EMPTY_CHAPTER] if none does.
 *
 * **[bookPosition] is a book offset, not a track one**, despite the track id alongside it: the
 * comparison is against [Chapter.bookStartTimeOffset]. The old parameter name, `timeStamp`, said
 * nothing about the frame, and `MultiTrackChapterTest` pins both halves of the ambiguity — a book
 * offset resolves, an in-track offset finds nothing. Now the type says it (cu-136).
 */
fun List<Chapter>.getChapterAt(
  trackId: String,
  bookPosition: BookOffset,
): Chapter {
  for (chapter in this) {
    // Half-open: `start <= t < end`. The range used to be inclusive at *both* ends, so a position
    // exactly on a boundary matched the **earlier** chapter — and the loop returns the first match.
    // Seeking to a chapter start lands precisely on that boundary, so pressing previous-chapter
    // seeked correctly to Chapter 20's start and then displayed "Chapter 19", which reads as the
    // button going to the end of the previous chapter (cu-93).
    //
    // This now agrees with [chapterAtBookProgress], which was already half-open. Two lookups over
    // the same data disagreeing at a boundary is the actual defect; the inclusive end was it.
    if (chapter.trackId == trackId && bookPosition >= chapter.bookStartTimeOffset && bookPosition < chapter.bookEndTimeOffset) {
      return chapter
    }
  }
  // The final chapter's own end is a real position — a book paused at its very last millisecond is
  // in the last chapter, not nowhere. Half-open excludes it, so accept it explicitly.
  return lastOrNull { it.trackId == trackId && bookPosition == it.bookEndTimeOffset } ?: EMPTY_CHAPTER
}

/**
 * The chapter containing [bookProgress] millis from the start of the book.
 *
 * The counterpart to [getChapterAt], which needs a track id *and* a timestamp inside that chapter's
 * span and returns [EMPTY_CHAPTER] when either does not match. This one needs only the book-level
 * position, so it answers for a book the user has not started playing in this session — where
 * `CurrentlyPlayingSingleton` has no current track to match on (cu-87).
 *
 * Offsets are absolute within the book (see [asChapterList]), so this is a plain range check.
 * Chapters are sorted first because the list arrives from the database and the network in no
 * guaranteed order. Returns the last chapter for a position at or past the end, rather than
 * [EMPTY_CHAPTER], so a finished book still reports where it finished.
 */
fun List<Chapter>.chapterAtBookProgress(bookProgress: BookOffset): Chapter {
  if (isEmpty()) {
    return EMPTY_CHAPTER
  }
  val ordered = sorted()
  return ordered.firstOrNull { bookProgress < it.bookEndTimeOffset } ?: ordered.last()
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
      bookStartTimeOffset = BookOffset(split[3].toLong()),
      bookEndTimeOffset = BookOffset(split[4].toLong()),
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
        it.bookStartTimeOffset.millis,
        it.bookEndTimeOffset.millis,
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
