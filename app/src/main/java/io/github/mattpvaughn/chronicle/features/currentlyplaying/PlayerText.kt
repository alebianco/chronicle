package io.github.mattpvaughn.chronicle.features.currentlyplaying

import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.util.formatCoarseDuration
import io.github.mattpvaughn.chronicle.util.formatPrecisePosition

/**
 * Resolves a string resource with its arguments.
 *
 * A function type rather than a `Context`: these formatters need exactly one capability, and
 * naming it keeps them callable from a unit test without standing up a `Context` or Robolectric.
 * At the call site it is `binding.root.context::getString` — or, in a Fragment, `::getString`.
 */
typealias StringResolver = (Int, Array<out Any>) -> String

private fun StringResolver.get(
  resId: Int,
  vararg args: Any,
): String = this(resId, args)

/**
 * The player's three text readouts, as pure functions over a [CurrentlyPlayingViewModel.PlayerProgress].
 *
 * Extracted from `CurrentlyPlayingFragment.onCreateView`, where they were local functions
 * closing over `binding`. Nothing about them needed a view — they take a progress snapshot and
 * return a string — but living inside a 408-line function made them **unreachable by any unit
 * test**, which is the mechanism the 2026-09-05 maintainability review identified behind this
 * package's coverage.
 *
 * `util/DurationFormat.kt` is the precedent: pure over millis, tested without a `Context`, and the
 * source of the wording rule these obey (§3.1 rule 3) — a two-level human-formatted readout
 * (`6h 12m left in book`), never a raw `h:mm:ss/h:mm:ss` pair. `RawDurationFormatTest` enforces
 * that the four progress views are written from `formatCoarseDuration`/`formatPrecisePosition`,
 * and these three are where that now happens.
 */
object PlayerText {
  /**
   * The book half of the readout: `6h 12m left in book`.
   *
   * The wording lives in `strings.xml` and the arithmetic in the ViewModel, so this only joins
   * them — which is why it takes a `PlayerProgress` rather than reading the ViewModel itself.
   */
  fun bookProgress(
    progress: CurrentlyPlayingViewModel.PlayerProgress?,
    strings: StringResolver,
  ): String {
    if (progress == null) return ""
    return strings.get(
      R.string.player_left_in_book,
      formatCoarseDuration(progress.millisLeftInBook),
    )
  }

  /**
   * The chapter's place in the book: `Ch 3 of 6`, or `No chapters` when the book has none.
   *
   * Not blank in that case: the old readout fell back to the track's raw position here, and
   * leaving it empty would drop information rather than reformat it. "Ch 0 of 0" would read as a
   * bug, so the state is named instead — the chapter title beside it already falls back to the
   * track's title.
   */
  fun chapterPosition(
    progress: CurrentlyPlayingViewModel.PlayerProgress?,
    strings: StringResolver,
  ): String {
    if (progress == null) return ""
    if (!progress.hasChapters) return strings.get(R.string.player_no_chapters)
    return strings.get(
      R.string.player_chapter_of,
      progress.chapterNumber,
      progress.chapterCount,
    )
  }

  /**
   * How much of the current chapter is left: `2:30 left in chapter`.
   *
   * With no chapters there is no chapter to count down, so this falls back to the **book**'s
   * remaining time rather than going blank — which is the more useful of the two anyway, and keeps
   * the line populated for a chapter-less book where the old readout showed the track's.
   */
  fun chapterRemaining(
    progress: CurrentlyPlayingViewModel.PlayerProgress?,
    strings: StringResolver,
  ): String {
    if (progress == null) return ""
    if (!progress.hasChapters) return bookProgress(progress, strings)
    return strings.get(
      R.string.player_left_in_chapter,
      formatPrecisePosition(progress.millisLeftInChapter),
    )
  }
}
