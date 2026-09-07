package io.github.mattpvaughn.chronicle.features.currentlyplaying

import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.MediaItemTrack
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The singleton resolves its chapter list table-first.
 *
 * This is the highest-leverage of the four read sites: `CurrentlyPlayingViewModel`'s six reads all
 * go through `currentlyPlaying.book.value.chapters` — *this* resolved field — so they move with it
 * rather than needing six separate changes.
 *
 * Rows are passed **in** rather than read from a DAO here. `update` is called once a second by
 * `ProgressUpdater` from the playback path, so giving this class a DAO would put a blocking read
 * on that tick — the exact shape the StateFlow migration removed. All three callers already run
 * in IO context.
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

  private val fromTable = listOf(chapter("c-tab", "from table"))

  /**
   * Real chapter rows beat the per-track derivation.
   *
   * The legacy column was the middle level here until it was dropped; what is left is the
   * distinction that still matters — a book with real chapters must not fall back to one chapter
   * per track, which is what `asChapterList()` produces.
   */
  @Test
  fun `table rows win over the per-track derivation`() {
    val s = CurrentlyPlayingSingleton()
    val tracks = listOf(track("1"))
    s.update(
      book = Audiobook(id = "b1", source = TEST_SOURCE, title = "Book"),
      track = tracks[0],
      tracks = tracks,
      chaptersFromTable = fromTable,
    )
    assertEquals("from table", s.chapter.value.title)
  }

  /** the fallback, which is permanent: a server reporting no chapters has nothing to use. */
  @Test
  fun `tracks remain the last resort`() {
    val s = CurrentlyPlayingSingleton()
    val tracks = listOf(track("1"))
    s.update(
      book = Audiobook(id = "b1", source = TEST_SOURCE, title = "Book"),
      track = tracks[0],
      tracks = tracks,
      chaptersFromTable = emptyList(),
    )
    assertEquals("t1", s.chapter.value.title)
  }
}
