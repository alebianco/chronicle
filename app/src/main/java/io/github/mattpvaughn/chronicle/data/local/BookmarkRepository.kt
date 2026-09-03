package io.github.mattpvaughn.chronicle.data.local

import androidx.lifecycle.LiveData
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Bookmark
import io.github.mattpvaughn.chronicle.util.DispatcherProvider
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The user's bookmarks (cu-22). */
interface IBookmarkRepository {
  fun getBookmarksForBook(bookId: String): LiveData<List<Bookmark>>

  suspend fun getBookmarksForBookAsync(bookId: String): List<Bookmark>

  /** Every bookmark, for the backup export. */
  suspend fun getAllAsync(): List<Bookmark>

  /**
   * Adds a bookmark at [position] in [bookId].
   *
   * [createdAt] defaults to now but is a parameter, so a test can pin it without this class
   * needing an injected clock — there is no clock binding in this graph and adding one for a
   * single timestamp is more machinery than the problem deserves.
   *
   * @return the bookmark that was stored, so a caller can offer to edit its note without
   *   re-reading — it is the only way to learn the generated id.
   */
  suspend fun add(
    bookId: String,
    position: BookOffset,
    note: String = "",
    createdAt: Long = System.currentTimeMillis(),
  ): Bookmark

  suspend fun updateNote(
    id: String,
    note: String,
  )

  suspend fun delete(id: String)

  /**
   * Merges bookmarks from a backup, keyed on id.
   *
   * Additive and idempotent, never destructive: restoring the same file twice overwrites the same
   * rows instead of duplicating them, and a restore leaves bookmarks made since the export alone.
   * Rows for books that are not in the library are kept — the library may be re-synced later, and
   * silently discarding a note the user wrote is the worst available outcome.
   *
   * @return how many were written.
   */
  suspend fun restore(bookmarks: List<Bookmark>): Int
}

@Singleton
class BookmarkRepository
  @Inject
  constructor(
    private val bookmarkDao: BookmarkDao,
    private val dispatchers: DispatcherProvider,
  ) : IBookmarkRepository {
    override fun getBookmarksForBook(bookId: String): LiveData<List<Bookmark>> = bookmarkDao.getBookmarksForBook(bookId)

    override suspend fun getBookmarksForBookAsync(bookId: String): List<Bookmark> =
      withContext(dispatchers.io) { bookmarkDao.getBookmarksForBookAsync(bookId) }

    override suspend fun getAllAsync(): List<Bookmark> = withContext(dispatchers.io) { bookmarkDao.getAllAsync() }

    override suspend fun add(
      bookId: String,
      position: BookOffset,
      note: String,
      createdAt: Long,
    ): Bookmark {
      val bookmark =
        Bookmark(
          bookId = bookId,
          position = position,
          note = note,
          createdAt = createdAt,
        )
      withContext(dispatchers.io) { bookmarkDao.insert(bookmark) }
      return bookmark
    }

    override suspend fun updateNote(
      id: String,
      note: String,
    ) {
      withContext(dispatchers.io) { bookmarkDao.updateNote(id, note) }
    }

    override suspend fun delete(id: String) {
      withContext(dispatchers.io) { bookmarkDao.delete(id) }
    }

    override suspend fun restore(bookmarks: List<Bookmark>): Int {
      if (bookmarks.isEmpty()) {
        return 0
      }
      withContext(dispatchers.io) { bookmarkDao.insertAll(bookmarks) }
      return bookmarks.size
    }
  }
