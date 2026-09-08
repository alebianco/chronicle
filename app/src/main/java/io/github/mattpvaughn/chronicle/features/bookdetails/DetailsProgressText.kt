package io.github.mattpvaughn.chronicle.features.bookdetails

import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookProgressState
import io.github.mattpvaughn.chronicle.data.model.progressState
import io.github.mattpvaughn.chronicle.features.currentlyplaying.StringResolver
import io.github.mattpvaughn.chronicle.util.formatCoarseDuration

/**
 * The book-details progress readout, in the three states the screen actually has.
 *
 * The screen used to print `00:00/9:26:42` — the literal `h:mm:ss/h:mm:ss` pair that
 * RESEARCH_FINDINGS §3.1 rule 3 rules out and that was removed from the player. This is the same
 * fix, arriving late because the details screen was never converted: `RawDurationFormatTest` was
 * deliberately scoped to the player's four progress views.
 *
 * **Why this is not simply the player's wording.** The player is only ever showing a book you are
 * listening to, so "6h 12m left in book" is always true there. This screen is most often looked at
 * for a book you have *not* started, where "9h 26m left" is a strange way to say the book is 9h 26m
 * long — it reads as though something has already happened. So the left-hand string is
 * state-dependent:
 *
 * | state | reads |
 * |---|---|
 * | not started | `9h 26m` — the total length, stated plainly |
 * | in progress | `6h 12m left` — the player's grammar, shortened for a narrow row |
 * | finished | `Finished` — the state named, rather than `0m left` |
 *
 * The percentage beside it is unchanged and carries the progress in every state, which is why the
 * length alone is enough for an unstarted book.
 *
 * **Which state a book is in is not decided here.** It takes a [BookProgressState] and only chooses
 * words for it. That matters more than it looks: this first branched on position, and a book the
 * user had *marked as played* rendered as **unstarted** — "Mark as played" zeroes the position on
 * both the tracks and the book row, so `progress == 0` describes a finished book and a never-opened
 * one alike. Decision-16 settles it: completion is an explicit fact (`viewCount`), never inferred
 * from position, and [Audiobook.progressState] is the single statement of that rule shared with the
 * library's progress indicators. Restating it here is how the two would drift — on a rule that was
 * itself a bug fix.
 *
 * `Finished` is likewise a **state, not a countdown**: the same book would otherwise read `0m left`,
 * which is a rounding bug rather than completion. How close to the end counts as finished belongs to
 * `BOOK_FINISHED_END_WINDOW`, for the same one-definition reason.
 *
 * Pure over a state, a duration and a [StringResolver], like `PlayerText` — the wording lives in
 * `strings.xml` (rule 5) and the arithmetic in the ViewModel, so this only joins them and stays
 * unit-testable without a `Context`.
 */
object DetailsProgressText {
  /**
   * The left-hand half of the progress row.
   *
   * [state] is the book's progress state, resolved by [Audiobook.progressState]; [durationMillis] is
   * its total length. A zero or negative [durationMillis] means neither the tracks nor the book row
   * have loaded yet, which is a real state on this screen — it returns empty rather than inventing
   * `0m`, so the row is blank while loading instead of briefly claiming the book is zero-length.
   */
  fun progress(
    state: BookProgressState,
    durationMillis: Long,
    strings: StringResolver,
  ): String {
    if (durationMillis <= 0L) return ""

    return when (state) {
      is BookProgressState.Completed -> strings.get(R.string.details_finished)
      is BookProgressState.Unstarted ->
        strings.get(R.string.details_total_length, formatCoarseDuration(durationMillis))
      is BookProgressState.InProgress ->
        strings.get(
          R.string.details_left,
          formatCoarseDuration(durationMillis - state.progressMillis),
        )
    }
  }
}

private fun StringResolver.get(
  resId: Int,
  vararg args: Any,
): String = this(resId, args)
