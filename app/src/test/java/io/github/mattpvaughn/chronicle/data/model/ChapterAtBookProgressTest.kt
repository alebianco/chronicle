package io.github.mattpvaughn.chronicle.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Finding the current chapter from a book-level position.
 *
 * `getChapterAt` needs a track id *and* a timestamp inside that chapter's span, and returns
 * `EMPTY_CHAPTER` when either does not match. `CurrentlyPlayingSingleton` published that empty
 * result, so on returning to a screen without playing, the chapter was empty or stale: the timeline
 * and the chapter-list highlight disagreed until playback started (cu-87).
 *
 * It is not only a display problem — `PlayerExt` drives skip-to-next-chapter and
 * skip-to-previous-chapter off the same value, so a stale chapter skips to the wrong place.
 */
class ChapterAtBookProgressTest {
  private fun chapter(
    title: String,
    index: Long,
    start: Long,
    end: Long,
    trackId: String = index.toString(),
  ) = Chapter(
    title = title,
    id = "4$index",
    index = index,
    discNumber = 1,
    startTimeOffset = start,
    endTimeOffset = end,
    trackId = trackId,
    bookId = "1001",
  )

  private val chapters =
    listOf(
      chapter("One", 1, 0L, 1_000L),
      chapter("Two", 2, 1_000L, 3_000L),
      chapter("Three", 3, 3_000L, 6_000L),
    )

  @Test
  fun `a position in the first chapter finds it`() {
    assertEquals("One", chapters.chapterAtBookProgress(500L).title)
  }

  @Test
  fun `a position in a middle chapter finds it`() {
    assertEquals("Two", chapters.chapterAtBookProgress(1_500L).title)
  }

  @Test
  fun `a position in the last chapter finds it`() {
    assertEquals("Three", chapters.chapterAtBookProgress(4_000L).title)
  }

  @Test
  fun `a position at the very start finds the first chapter`() {
    assertEquals("One", chapters.chapterAtBookProgress(0L).title)
  }

  /** Exactly on a boundary belongs to the chapter that is starting, not the one that ended. */
  @Test
  fun `a position on a chapter boundary belongs to the later chapter`() {
    assertEquals("Two", chapters.chapterAtBookProgress(1_000L).title)
  }

  /**
   * A finished book must still report where it finished, rather than falling back to nothing —
   * which is what would leave the highlight empty at the end of a book.
   */
  @Test
  fun `a position past the end reports the last chapter`() {
    assertEquals("Three", chapters.chapterAtBookProgress(99_000L).title)
  }

  /** The list arrives from the DB and the network in no guaranteed order. */
  @Test
  fun `list order does not matter`() {
    val shuffled = listOf(chapters[2], chapters[0], chapters[1])

    assertEquals("Two", shuffled.chapterAtBookProgress(1_500L).title)
  }

  @Test
  fun `an empty chapter list yields the empty chapter`() {
    assertEquals(EMPTY_CHAPTER, emptyList<Chapter>().chapterAtBookProgress(1_000L))
  }

  /**
   * The exact case that reached the owner's device (cu-73).
   *
   * A hand-rolled walk in two ViewModels subtracted each chapter's *duration* from a running offset
   * while comparing against the **absolute** `endTimeOffset`. At 28,359,976ms in a real 40-chapter
   * book it resolved Chapter 12 (ending 15,803,900) instead of Chapter 20 — a cold start showed the
   * wrong chapter until playback corrected it.
   *
   * The numbers here are the real ones from that book, so this fails if anyone reintroduces the
   * relative-offset walk.
   */
  @Test
  fun `a late position in a long book resolves its real chapter`() {
    val realBook =
      listOf(
        chapter("Chapter 11", index = 11L, start = 13_411_400L, end = 14_711_100L),
        chapter("Chapter 12", index = 12L, start = 14_711_100L, end = 15_803_900L),
        chapter("Chapter 19", index = 19L, start = 25_024_800L, end = 26_879_000L),
        chapter("Chapter 20", index = 20L, start = 26_879_000L, end = 29_184_600L),
        chapter("Chapter 21", index = 21L, start = 29_184_600L, end = 31_300_300L),
      )

    val resolved = realBook.chapterAtBookProgress(28_359_976L)

    assertEquals(
      "offsets are absolute; subtracting durations picks a far earlier chapter",
      "Chapter 20",
      resolved.title,
    )
  }
}
