package io.github.mattpvaughn.chronicle.data.local

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.Chapter

/**
 * Decides what a one-off chapter backfill should write (cu-158).
 *
 * Chapters live in `ChapterDatabase` *and* on `Audiobook.chapters` (cu-49), and the table fills
 * **lazily** — `BookRepository.syncAudiobook` is its only writer and runs per book when its tracks
 * load. So a library synced by an earlier version has an empty table, which is what would force
 * every read site into a permanent table → column → `asChapterList()` chain (cu-82). Copying the
 * column into the table once removes that reason.
 *
 * Pure and free of Android and Room types so the rules are testable directly; the caller owns the
 * I/O and the scheduling.
 */
object ChapterBackfill {
  /**
   * The rows to write for [book], or empty when it needs nothing.
   *
   * Returns empty when the book already has [existingRowCount] rows: **the table wins, never the
   * column.** A book synced by a current version has authoritative rows, and re-writing them from
   * a column that may be staler is a regression, not a repair.
   *
   * Every returned row carries `bookId = book.id`. That is the load-bearing part rather than a
   * tidy-up: `Chapter`'s primary key is `(bookId, trackId, discNumber, index)`, and a chapter
   * record serialized by a version before the id was added decodes with `bookId` set to
   * `NO_AUDIOBOOK_FOUND_ID` (`Chapter.decodeChapter` defaults it). Inserting those verbatim would
   * key every book's chapters under the same sentinel, so they would collide and overwrite each
   * other — one book's chapters would silently replace another's.
   *
   * Offsets are copied **unchanged**. They are absolute within the book, not per-track
   * (cu-13/cu-49, and cu-136 made the frame part of the type); this moves rows, it does not
   * recompute them.
   */
  fun rowsFor(
    book: Audiobook,
    existingRowCount: Int,
  ): List<Chapter> {
    if (existingRowCount > 0) {
      return emptyList()
    }
    return book.chapters
      .filter { it.title.isNotEmpty() || it.index > 0 }
      .map { it.copy(bookId = book.id) }
  }

  /**
   * Whether [book] is worth even querying the table for.
   *
   * Cheap pre-filter so the common case — a library already synced by a current version — costs
   * one field read per book instead of a query. A book with no chapter data at all is not a
   * failure: it keeps the `asChapterList()` fallback that cu-13 added, which stays regardless.
   */
  fun mayNeedBackfill(book: Audiobook): Boolean = book.chapters.isNotEmpty()
}
