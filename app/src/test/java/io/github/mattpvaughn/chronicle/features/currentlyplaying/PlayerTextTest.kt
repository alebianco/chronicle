package io.github.mattpvaughn.chronicle.features.currentlyplaying

import io.github.mattpvaughn.chronicle.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The player's three text readouts, now testable.
 *
 * These lived as local functions inside `CurrentlyPlayingFragment.onCreateView` — a 408-line
 * function — where nothing about them needed a view but **no unit test could reach them**. That is
 * the mechanism the 2026-09-05 review identified behind this package's coverage, and this suite is
 * the first half of the fix.
 *
 * The [StringResolver] is a fake that renders `resId(args)`, so each assertion pins **which
 * resource** was chosen and **what was interpolated** — the two things that can be wrong — without
 * a `Context` or Robolectric. Asserting the English wording instead would pin `strings.xml` rather
 * than the branch, and would fail under a translation.
 */
class PlayerTextTest {
  private val strings: StringResolver = { resId, args -> "$resId(${args.joinToString(",")})" }

  private fun progress(
    chapterNumber: Int = 3,
    chapterCount: Int = 6,
    millisLeftInChapter: Long = 150_000L,
    millisLeftInBook: Long = 22_320_000L,
  ) = CurrentlyPlayingViewModel.PlayerProgress(
    chapterNumber = chapterNumber,
    chapterCount = chapterCount,
    millisLeftInChapter = millisLeftInChapter,
    millisLeftInBook = millisLeftInBook,
  )

  /**
   * A null snapshot is "not playing yet", not an error. Every one of the three returns empty so the
   * view stays blank rather than rendering a placeholder the user would read as real.
   */
  @Test
  fun `a null progress renders every readout empty`() {
    assertEquals("", PlayerText.bookProgress(null, strings))
    assertEquals("", PlayerText.chapterPosition(null, strings))
    assertEquals("", PlayerText.chapterRemaining(null, strings))
  }

  @Test
  fun `book progress is coarse and human, never a raw pair`() {
    val text = PlayerText.bookProgress(progress(millisLeftInBook = 22_320_000L), strings)

    // 6h 12m — `formatCoarseDuration`, per §3.1 rule 3.
    assertEquals("${R.string.player_left_in_book}(6h 12m)", text)
  }

  @Test
  fun `chapter position names the chapter and the count`() {
    val text = PlayerText.chapterPosition(progress(chapterNumber = 3, chapterCount = 6), strings)

    assertEquals("${R.string.player_chapter_of}(3,6)", text)
  }

  /**
   * With no chapters the state is **named**, not blank and not `Ch 0 of 0` — the latter reads as a
   * bug, and blank would drop information the old track-based readout used to carry.
   */
  @Test
  fun `a book with no chapters says so rather than rendering zeroes`() {
    val text = PlayerText.chapterPosition(progress(chapterNumber = 0, chapterCount = 0), strings)

    assertEquals("${R.string.player_no_chapters}()", text)
  }

  /**
   * `hasChapters` requires **both** a positive count and a positive number, so a book reporting a
   * count with no current chapter — the state between opening a book and resolving its position —
   * must also take the no-chapters branch rather than rendering `Ch 0 of 6`.
   */
  @Test
  fun `a chapter count with no current chapter still reads as no chapters`() {
    val text = PlayerText.chapterPosition(progress(chapterNumber = 0, chapterCount = 6), strings)

    assertEquals("${R.string.player_no_chapters}()", text)
  }

  @Test
  fun `chapter remaining is a precise position inside the chapter`() {
    val text = PlayerText.chapterRemaining(progress(millisLeftInChapter = 150_000L), strings)

    // 2:30 — `formatPrecisePosition`, the other half of the two-level readout.
    assertEquals("${R.string.player_left_in_chapter}(2:30)", text)
  }

  /**
   * The fallback that keeps the line populated: with no chapters there is nothing to count down,
   * so this shows the **book**'s remaining time rather than going blank. Pinned because it is the
   * one place the two formatters are coupled — a change to `bookProgress` silently changes this.
   */
  @Test
  fun `with no chapters the chapter line falls back to the book's remaining time`() {
    val noChapters = progress(chapterNumber = 0, chapterCount = 0, millisLeftInBook = 22_320_000L)

    assertEquals(
      PlayerText.bookProgress(noChapters, strings),
      PlayerText.chapterRemaining(noChapters, strings),
    )
  }

  @Test
  fun `a chapter under a minute still renders a precise position`() {
    val text = PlayerText.chapterRemaining(progress(millisLeftInChapter = 42_000L), strings)

    assertEquals("${R.string.player_left_in_chapter}(0:42)", text)
  }

  /**
   * A 47-hour book is the case §3.1 rule 3 was written for — the old readout showed
   * `47:12:33/52:04:11` here.
   */
  @Test
  fun `a very long book reads in hours and minutes`() {
    val text = PlayerText.bookProgress(progress(millisLeftInBook = 169_953_000L), strings)

    assertEquals("${R.string.player_left_in_book}(47h 12m)", text)
  }
}
