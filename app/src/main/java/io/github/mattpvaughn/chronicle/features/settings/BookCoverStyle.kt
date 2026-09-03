package io.github.mattpvaughn.chronicle.features.settings

import androidx.annotation.StringRes
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo

/**
 * Whether book covers render square or rectangular, as the settings screen presents it.
 *
 * Extracted from `SettingsViewModel.makePreferences()` (cu-101), the same two-way-mapping shape as
 * [RefreshRate] — but this one was **broken**, not merely untestable.
 *
 * The chooser's listener wrote raw literals, `"Rectangle"` and `"Square"`, while all four consumers
 * compare against [PrefsRepo.BOOK_COVER_STYLE_RECT] / [PrefsRepo.BOOK_COVER_STYLE_SQUARE] — and the
 * rectangular constant is `"Rectangular"`. Choosing the rectangular style therefore stored a value
 * matching *neither* constant, and `BOOK_COVER_STYLE_RECT` was dead code in `main`.
 *
 * It hid because every consumer asks `== BOOK_COVER_STYLE_SQUARE` and treats anything else as
 * rectangular, so the covers still looked right. What the user actually saw was the settings row,
 * which interpolates the stored string directly: the chooser offered "Rectangular" and the row then
 * read *"Book cover style: Rectangle"*.
 *
 * The stored form is a **persisted English literal**, not a label — it predates this change and is
 * in the settings-backup allowlist ([PrefsRepo.KEY_BOOK_COVER_STYLE]), so it cannot be renamed
 * without migrating existing installs and exports. [choiceRes] is the localized text; [stored] is
 * the on-disk fact. Keeping them distinct is what the original conflated.
 */
enum class BookCoverStyle(
  /** The persisted value. Must remain one of `PrefsRepo`'s two constants. */
  val stored: String,
  /** The option as offered in the chooser. */
  @StringRes val choiceRes: Int,
) {
  Square(
    stored = PrefsRepo.BOOK_COVER_STYLE_SQUARE,
    choiceRes = R.string.settings_book_cover_type_square,
  ),
  Rectangular(
    stored = PrefsRepo.BOOK_COVER_STYLE_RECT,
    choiceRes = R.string.settings_book_cover_type_rect,
  ),
  ;

  /** What the four cover-rendering call sites actually ask. */
  val isSquare: Boolean get() = stored == PrefsRepo.BOOK_COVER_STYLE_SQUARE

  companion object {
    /** The chooser's options, in the order they are offered. */
    val choices: List<BookCoverStyle> = entries.toList()

    /** What a fresh install renders, matching `SharedPreferencesPrefsRepo`'s own default. */
    val default: BookCoverStyle = Square

    /** The style [choiceRes] identifies, or null if it names no option. */
    fun ofChoice(
      @StringRes choiceRes: Int,
    ): BookCoverStyle? = entries.firstOrNull { it.choiceRes == choiceRes }

    /** The style [stored] represents, or null if it is not a value this app writes. */
    fun ofStored(stored: String): BookCoverStyle? = entries.firstOrNull { it.stored == stored }

    /**
     * [ofStored], falling back to [default] rather than throwing.
     *
     * Three ways an unrecognized value reaches this: an install that stored `"Rectangle"` before
     * the fix, a hand-edited settings export (the key is allowlisted as a bare `STRING` with no
     * value validation — cu-133), and any future rename. The original `when` ended in
     * `throw NoWhenBranchMatchedException`, which is the wrong answer for a cosmetic preference.
     */
    fun ofStoredOrDefault(stored: String): BookCoverStyle = ofStored(stored) ?: default
  }
}
