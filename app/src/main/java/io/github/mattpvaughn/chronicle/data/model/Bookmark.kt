package io.github.mattpvaughn.chronicle.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import java.util.UUID

/**
 * A position in a book the user marked, with an optional note (cu-22).
 *
 * Lives in its own database rather than as a table in `BookDatabase`, and that is load-bearing: a
 * library refresh merges `Audiobook` rows and calls `bookDao.removeAll` for books the server no
 * longer lists, so anything stored alongside a book is exposed to the sync path. A bookmark is
 * something the *user* wrote and the server knows nothing about — losing it to a re-sync would be
 * unrecoverable, so it is kept somewhere a sync cannot reach.
 */
@Entity
@TypeConverters(OffsetConverters::class)
data class Bookmark(
  /**
   * A generated id, not derived from the position.
   *
   * Two bookmarks may legitimately mark the same moment, and editing a note must not change a
   * bookmark's identity — which it would if the key were `(bookId, position)`. `String` like every
   * other id in this schema (cu-71, decision-11).
   */
  @PrimaryKey
  val id: String = UUID.randomUUID().toString(),
  /** The book this marks. The only link to the catalogue, so a re-sync cannot orphan by rowid. */
  val bookId: String,
  /**
   * Where in the **book** this points, not where in a track.
   *
   * A `BookOffset` because cu-136 made the frame a type after six bugs from confusing the two.
   * Stored as a plain `INTEGER` via [OffsetConverters], so no migration is implied by the wrapper.
   */
  val position: BookOffset,
  /** The user's note. Empty is normal: a bookmark with no note is still a useful bookmark. */
  val note: String = "",
  /** When it was made, as Unix millis. Gives the list a stable order within one position. */
  val createdAt: Long = 0L,
) {
  /** Whether this bookmark carries a note, for a UI that shows them differently. */
  val hasNote: Boolean
    get() = note.isNotBlank()
}
