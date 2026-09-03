package io.github.mattpvaughn.chronicle.features.settings

import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The book-cover-style mapping, both directions (cu-101).
 *
 * This is the second of the two-way mappings buried in `makePreferences()`, and unlike the refresh
 * rate it was **actively broken**. The chooser wrote raw literals:
 *
 * ```
 * R.string.settings_book_cover_type_rect -> prefsRepo.bookCoverStyle = "Rectangle"
 * ```
 *
 * while every consumer compares against `PrefsRepo.BOOK_COVER_STYLE_RECT`, which is
 * `"Rectangular"`. The stored value therefore matched neither constant, and
 * `BOOK_COVER_STYLE_RECT` was dead in `main` — referenced only by a backup test.
 *
 * It survived because the four consumers all ask `== BOOK_COVER_STYLE_SQUARE`, so an unrecognized
 * value falls to the rectangular branch and *looks* right. The visible damage was the settings row
 * itself, which interpolates the stored string: the chooser offered "Rectangular" and the row then
 * read "Book cover style: Rectangle".
 */
class BookCoverStyleTest {
  @Test
  fun `every offered choice stores a value the consumers recognize`() {
    BookCoverStyle.choices.forEach { style ->
      assertEquals(
        "${style.name} must store one of the two values consumers compare against",
        true,
        style.stored == PrefsRepo.BOOK_COVER_STYLE_SQUARE ||
          style.stored == PrefsRepo.BOOK_COVER_STYLE_RECT,
      )
    }
  }

  /** The bug: the chooser's rectangular option must store the constant, not a near-miss. */
  @Test
  fun `the rectangular choice stores the rectangular constant`() {
    assertEquals(PrefsRepo.BOOK_COVER_STYLE_RECT, BookCoverStyle.Rectangular.stored)
  }

  @Test
  fun `the square choice stores the square constant`() {
    assertEquals(PrefsRepo.BOOK_COVER_STYLE_SQUARE, BookCoverStyle.Square.stored)
  }

  @Test
  fun `every choice resolves from its own resource`() {
    BookCoverStyle.choices.forEach { style ->
      assertEquals(style, BookCoverStyle.ofChoice(style.choiceRes))
    }
  }

  @Test
  fun `an unrelated resource resolves to no choice`() {
    assertNull(BookCoverStyle.ofChoice(R.string.settings_category_sync))
  }

  /** The round trip: what the chooser stores must read back as the same choice. */
  @Test
  fun `every stored value round-trips back to its choice`() {
    BookCoverStyle.choices.forEach { style ->
      assertEquals(style, BookCoverStyle.ofStored(style.stored))
    }
  }

  /**
   * A value from an older install — or a hand-edited settings export — must not throw. The
   * original `when` ended in `throw NoWhenBranchMatchedException`, and this key is allowlisted for
   * import as a bare `STRING` with no value validation (cu-133).
   */
  @Test
  fun `an unknown stored value falls back to the default rather than throwing`() {
    assertEquals(BookCoverStyle.Square, BookCoverStyle.ofStoredOrDefault("Rectangle"))
    assertEquals(BookCoverStyle.Square, BookCoverStyle.ofStoredOrDefault(""))
  }

  @Test
  fun `stored values are unique`() {
    val stored = BookCoverStyle.choices.map { it.stored }
    assertEquals(stored.size, stored.toSet().size)
  }

  @Test
  fun `isSquare agrees with the stored square constant`() {
    assertEquals(true, BookCoverStyle.Square.isSquare)
    assertEquals(false, BookCoverStyle.Rectangular.isSquare)
  }
}
