package io.github.mattpvaughn.chronicle.data.model

/**
 * What a book's progress indicator should show — the decision, with no view attached.
 *
 * Extracted from `bindProgressIndicators`, which is `View`/`ProgressBar`-typed and so unreachable
 * from Compose. As the screens migrate one at a time the two renderers coexist for months,
 * and re-implementing this branching on the Compose side is how they would drift — on a rule that
 * was itself a bug fix, which is the worst kind to get subtly wrong.
 *
 * Three states, not two booleans, because the interesting case is a book
 * **marked as read at zero progress** is finished, not unstarted. Completion is an explicit fact
 * (`viewCount`), never inferred from position — decision-16.
 */
sealed interface BookProgressState {
  /** Never opened: the dog-ear shows, the bar does not. */
  data object Unstarted : BookProgressState

  /** Part way through: the bar shows at [progressMillis], no dog-ear. */
  data class InProgress(val progressMillis: Long) : BookProgressState

  /** Finished: a full bar, no dog-ear — however little position the server reports. */
  data object Completed : BookProgressState
}

/**
 * The single statement of the rule, shared by the View and Compose renderers.
 *
 * Completion is checked first for readability, but the two branches are in fact **mutually
 * exclusive**: `isCompleted()` returns true whenever `viewCount > 0`, and `Unstarted` requires
 * `viewCount == 0`. So a book marked as read at zero progress reads as finished either way — the
 * ordering is not what protects this, `isCompleted()`'s own `viewCount` check is. Verified by
 * swapping the branches and watching all five `ProgressIndicatorTest` cases still pass.
 */
fun Audiobook.progressState(): BookProgressState =
  when {
    isCompleted() -> BookProgressState.Completed
    viewCount == 0L && progress == 0L -> BookProgressState.Unstarted
    else -> BookProgressState.InProgress(progress)
  }

/**
 * The bar's maximum, floored at 1.
 *
 * A book with no duration would otherwise give a zero-length bar, which Android renders as *full*
 * — a book that has never been opened looking finished (the `a book with no duration does not
 * render as finished`).
 */
fun Audiobook.progressBarMax(): Int = duration.toInt().coerceAtLeast(1)
