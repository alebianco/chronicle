package io.github.mattpvaughn.chronicle.features.currentlyplaying

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ProgressUpdater` calls `update` once a second **without** table rows, on purpose: a DAO read per
 * tick is the cost cu-110 removed. That makes the interleaving load-bearing, so it is pinned here.
 *
 * The hazard: `OnMediaChangedCallback` resolves a book's chapters from the table, then a tick
 * arrives a moment later carrying only the legacy column. If that tick re-resolved, the list would
 * silently *downgrade* to the stale column — a regression invisible to every other test, since both
 * sources are usually identical.
 */
class CurrentlyPlayingChapterSourceOrderingTest {
  private fun track(id: String) = MediaItemTrack(id = id, parentKey = "b1", title = "t$id", index = id.toInt(), duration = 180_000L)

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

  @Test
  fun `a progress tick without table rows does not downgrade an already-resolved list`() {
    val s = CurrentlyPlayingSingleton()
    val tracks = listOf(track("1"))
    val book = Audiobook(id = "b1", source = TEST_SOURCE, title = "Book", chapters = listOf(chapter("c-col", "from column")))

    // OnMediaChangedCallback: rows available, so the table wins.
    s.update(book = book, track = tracks[0], tracks = tracks, chaptersFromTable = listOf(chapter("c-tab", "from table")))
    assertEquals("from table", s.chapter.value.title)

    // ProgressUpdater a second later: same shape, no rows passed. Must change nothing.
    s.update(book = book, track = tracks[0], tracks = tracks)
    assertEquals("from table", s.chapter.value.title)
  }
}
