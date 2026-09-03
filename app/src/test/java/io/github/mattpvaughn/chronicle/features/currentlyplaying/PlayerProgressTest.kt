package io.github.mattpvaughn.chronicle.features.currentlyplaying

import io.github.mattpvaughn.chronicle.data.model.BookOffset
import io.github.mattpvaughn.chronicle.data.model.Chapter
import io.github.mattpvaughn.chronicle.data.model.EMPTY_CHAPTER
import io.github.mattpvaughn.chronicle.testing.MultiTrackBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-level progress readout (cu-19).
 *
 * RESEARCH_FINDINGS §3.1's convergent-grammar rule 3 asks for chapter position **and**
 * time-left-in-book, never raw `h:mm:ss/h:mm:ss`. These pin the arithmetic behind that; the
 * wording is `DurationFormatTest`'s and the sentence assembly is the fragment's.
 *
 * Driven against `MultiTrackBook`, where the chapter frame and the book frame are *different
 * numbers* — six 5-minute chapters over three 10-minute tracks. On a single-track book they
 * coincide and a mix-up is invisible, which is how six of them shipped (cu-136).
 */
class PlayerProgressTest {
  private val chapters = MultiTrackBook.chapters()
  private val bookDuration = MultiTrackBook.BOOK_DURATION

  /**
   * Position 750_000 sits inside chapter 3 (600_000..900_000) of an 1_800_000ms book, so
   * 150_000 remains in the chapter and 1_050_000 in the book. Two different numbers, which is the
   * whole point of the readout being two-level.
   */
  @Test
  fun `the chapter and the book are reported separately`() {
    val progress =
      playerProgressOf(
        chapter = chapters[2],
        bookPosition = MultiTrackBook.MID_BOOK_OFFSET,
        chapterList = chapters,
        bookDurationMillis = bookDuration,
      )

    assertEquals(3, progress.chapterNumber)
    assertEquals(6, progress.chapterCount)
    assertEquals(150_000L, progress.millisLeftInChapter)
    assertEquals(1_050_000L, progress.millisLeftInBook)
    assertTrue(progress.hasChapters)
  }

  @Test
  fun `the first chapter is number one, not zero`() {
    val progress =
      playerProgressOf(
        chapter = chapters[0],
        bookPosition = BookOffset.ZERO,
        chapterList = chapters,
        bookDurationMillis = bookDuration,
      )

    assertEquals(1, progress.chapterNumber)
    assertEquals(300_000L, progress.millisLeftInChapter)
    assertEquals(bookDuration, progress.millisLeftInBook)
  }

  @Test
  fun `the last chapter is the last number`() {
    val progress =
      playerProgressOf(
        chapter = chapters[5],
        bookPosition = BookOffset(1_500_000L),
        chapterList = chapters,
        bookDurationMillis = bookDuration,
      )

    assertEquals(6, progress.chapterNumber)
    assertEquals(6, progress.chapterCount)
    assertEquals(300_000L, progress.millisLeftInChapter)
  }

  /**
   * The chapter list arrives from the DB and the network in no guaranteed order, so the number has
   * to come from the *sorted* list. A label that depends on query order is wrong at random — the
   * same shape as the sorted/unsorted index bugs cu-136 closed.
   */
  @Test
  fun `the chapter number does not depend on the order the list arrives in`() {
    val shuffled = listOf(chapters[4], chapters[0], chapters[2], chapters[5], chapters[1], chapters[3])

    val fromOrdered =
      playerProgressOf(chapters[2], MultiTrackBook.MID_BOOK_OFFSET, chapters, bookDuration)
    val fromShuffled =
      playerProgressOf(chapters[2], MultiTrackBook.MID_BOOK_OFFSET, shuffled, bookDuration)

    assertEquals(fromOrdered.chapterNumber, fromShuffled.chapterNumber)
    assertEquals(3, fromShuffled.chapterNumber)
  }

  /** A book with no chapters must not read "Ch 0 of 0"; the view omits the part instead. */
  @Test
  fun `a book with no chapters reports none`() {
    val progress =
      playerProgressOf(
        chapter = EMPTY_CHAPTER,
        bookPosition = BookOffset.ZERO,
        chapterList = emptyList(),
        bookDurationMillis = bookDuration,
      )

    assertFalse(progress.hasChapters)
    assertEquals(0, progress.chapterCount)
    assertEquals(0, progress.chapterNumber)
    assertEquals(0L, progress.millisLeftInChapter)
  }

  /** A null chapter — the state before playback has resolved one — behaves the same way. */
  @Test
  fun `a null chapter reports no chapter position`() {
    val progress = playerProgressOf(null, BookOffset.ZERO, chapters, bookDuration)

    assertFalse(progress.hasChapters)
    assertEquals(0, progress.chapterNumber)
    assertEquals(0L, progress.millisLeftInChapter)
    // The book half still works: not knowing the chapter says nothing about the book.
    assertEquals(bookDuration, progress.millisLeftInBook)
  }

  /**
   * A chapter that is not in the list still reports the book correctly. Reachable while the list
   * is being replaced by a sync, and dropping the whole readout for it would blank the player.
   */
  @Test
  fun `a chapter absent from the list still reports the book`() {
    val stranger = Chapter(id = "999", trackId = "9999", bookStartTimeOffset = BookOffset.ZERO)

    val progress =
      playerProgressOf(stranger, MultiTrackBook.MID_BOOK_OFFSET, chapters, bookDuration)

    assertEquals(0, progress.chapterNumber)
    assertEquals(6, progress.chapterCount)
    assertEquals(1_050_000L, progress.millisLeftInBook)
  }

  /**
   * Remaining time never goes negative. A position past the recorded end is reachable — a
   * partly-synced book, or a duration the server revised down — and a negative readout is worse
   * than zero.
   */
  @Test
  fun `remaining time clamps at zero rather than going negative`() {
    val progress =
      playerProgressOf(chapters[0], BookOffset(99_999_999L), chapters, bookDuration)

    assertEquals(0L, progress.millisLeftInChapter)
    assertEquals(0L, progress.millisLeftInBook)
  }

  /** An unknown book length reports zero left rather than a negative. */
  @Test
  fun `an unknown book duration reports nothing left in the book`() {
    val progress =
      playerProgressOf(chapters[2], MultiTrackBook.MID_BOOK_OFFSET, chapters, 0L)

    assertEquals(0L, progress.millisLeftInBook)
    // The chapter half is unaffected — it does not depend on the book's total.
    assertEquals(150_000L, progress.millisLeftInChapter)
  }
}
