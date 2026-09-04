package io.github.mattpvaughn.chronicle.features.currentlyplaying

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The singleton resolves its chapter list table-first (cu-82).
 *
 * This is the highest-leverage of the four read sites: `CurrentlyPlayingViewModel`'s six reads all
 * go through `currentlyPlaying.book.value.chapters` — *this* resolved field — so they move with it
 * rather than needing six separate changes.
 *
 * Rows are passed **in** rather than read from a DAO here. `update` is called once a second by
 * `ProgressUpdater` from the playback path, so giving this class a DAO would put a blocking read on
 * that tick — the exact shape cu-110 removed. All three callers already run in IO context.
 */
class CurrentlyPlayingChapterSourceTest {
  private fun track(
    id: String,
    duration: Long = 180_000L,
  ) = MediaItemTrack(id = id, parentKey = "b1", title = "t$id", index = id.toInt(), duration = duration)

  private fun chapter(
    id: String,
    title: String,
  ) = Chapter(
    id = id,
    bookId = "b1",
    trackId = "1",
    title = title,
    index = 1L,
    discNumber = 1,
    bookStartTimeOffset = BookOffset.ZERO,
    bookEndTimeOffset = BookOffset(180_000L),
  )

  private val fromColumn = listOf(chapter("c-col", "from column"))
  private val fromTable = listOf(chapter("c-tab", "from table"))

  @Test
  fun `table rows win over the legacy column`() {
    val s = CurrentlyPlayingSingleton()
    val tracks = listOf(track("1"))
    s.update(
      book = Audiobook(id = "b1", source = 1L, title = "Book", chapters = fromColumn),
      track = tracks[0],
      tracks = tracks,
      chaptersFromTable = fromTable,
    )
    assertEquals("from table", s.chapter.value.title)
  }

  /** The upgrade case: the backfill has not reached this book, so the column is all there is. */
  @Test
  fun `the column is used when the table has no rows for the book`() {
    val s = CurrentlyPlayingSingleton()
    val tracks = listOf(track("1"))
    s.update(
      book = Audiobook(id = "b1", source = 1L, title = "Book", chapters = fromColumn),
      track = tracks[0],
      tracks = tracks,
      chaptersFromTable = emptyList(),
    )
    assertEquals("from column", s.chapter.value.title)
  }

  /** cu-13's fallback still applies when neither source has anything. */
  @Test
  fun `tracks remain the last resort`() {
    val s = CurrentlyPlayingSingleton()
    val tracks = listOf(track("1"))
    s.update(
      book = Audiobook(id = "b1", source = 1L, title = "Book"),
      track = tracks[0],
      tracks = tracks,
      chaptersFromTable = emptyList(),
    )
    assertEquals("t1", s.chapter.value.title)
  }
}
