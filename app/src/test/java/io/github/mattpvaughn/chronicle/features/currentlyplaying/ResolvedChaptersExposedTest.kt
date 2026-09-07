package io.github.mattpvaughn.chronicle.features.currentlyplaying

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The resolved chapter list must be readable, not just used internally.
 *
 * Ten call sites — five in `PlayerExt` (chapter skip) and five in `CurrentlyPlayingViewModel` —
 * read `currentlyPlaying.book.value.chapters`, the **legacy column**. Since chapters are written
 * to `ChapterDatabase`, that column is empty for any freshly synced book, so `indexOf` returns -1
 * and chapter skip silently does nothing. They must read the resolved list instead.
 */
class ResolvedChaptersExposedTest {
  private fun track(id: String) = MediaItemTrack(id = id, parentKey = "b1", title = "t$id", index = id.toInt(), duration = 180_000L)

  private fun chapter(
    id: String,
    index: Long,
  ) = Chapter(
    id = id,
    bookId = "b1",
    trackId = "1",
    title = "Chapter $index",
    index = index,
    discNumber = 1,
    bookStartTimeOffset = BookOffset(index * 1000),
    bookEndTimeOffset = BookOffset(index * 1000 + 1000),
  )

  /**
   * The resolved list must be exposed, not recomputed by each caller.
   *
   * Ten call sites in `PlayerExt` and `CurrentlyPlayingViewModel` used to read the book's legacy
   * column, which was empty for any book synced since chapters moved to the table — so
   * `indexOf` returned -1 and chapter skip silently did nothing. The column is gone; the exposure
   * it forced stays.
   */
  @Test
  fun `the resolved list is exposed for a book whose chapters live only in the table`() {
    val s = CurrentlyPlayingSingleton()
    val tracks = listOf(track("1"))
    val rows = listOf(chapter("c1", 1), chapter("c2", 2), chapter("c3", 3))

    s.update(
      book = Audiobook(id = "b1", source = TEST_SOURCE, title = "Book"),
      track = tracks[0],
      tracks = tracks,
      chaptersFromTable = rows,
    )

    assertEquals("the resolved list must carry the table's rows", rows, s.chapters)
    assertEquals("indexOf must resolve against the exposed list", 1, s.chapters.indexOf(rows[1]))
  }
}
