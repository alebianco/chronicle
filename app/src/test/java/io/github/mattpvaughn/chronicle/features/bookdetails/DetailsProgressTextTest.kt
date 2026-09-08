package io.github.mattpvaughn.chronicle.features.bookdetails

import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.BookProgressState
import io.github.mattpvaughn.chronicle.data.model.progressState
import io.github.mattpvaughn.chronicle.features.currentlyplaying.StringResolver
import io.github.mattpvaughn.chronicle.testing.TEST_SOURCE
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The three states of the details progress line, and which words each one gets.
 *
 * The wording is the owner's call: length when unstarted, the player's "left" grammar once started,
 * and `Finished` at the end. What is pinned here is not the phrasing — `strings.xml` owns that — but
 * **which resource each state selects**, since that is where the reasoning is and where a change
 * would be silent.
 *
 * Which *state* a book is in is not this object's decision and is not tested here; that rule lives
 * in `Audiobook.progressState` and is covered by `ProgressIndicatorTest`. The last three cases are
 * the seam between the two, because getting that wrong is exactly the bug this had.
 */
class DetailsProgressTextTest {
  /**
   * The same fake `PlayerTextTest` uses: renders `resId(args)`, so an assertion pins **which
   * resource** was chosen and **what was interpolated** — the two things that can be wrong —
   * without a `Context` or Robolectric.
   *
   * Deliberately not a `when` mapping ids to readable names: under a plain JVM test the `R` fields
   * are not guaranteed to be distinct, and a mapping that collapsed would make every state look
   * alike and every assertion here pass. The raw id cannot collapse.
   */
  private val strings: StringResolver = { resId, args -> "$resId(${args.joinToString(",")})" }

  private fun progress(
    state: BookProgressState,
    durationMillis: Long,
  ) = DetailsProgressText.progress(state, durationMillis, strings)

  private fun length(formatted: String) = "${R.string.details_total_length}($formatted)"

  private fun left(formatted: String) = "${R.string.details_left}($formatted)"

  private fun finished() = "${R.string.details_finished}()"

  private fun hours(h: Long) = TimeUnit.HOURS.toMillis(h)

  private fun minutes(m: Long) = TimeUnit.MINUTES.toMillis(m)

  private fun book(
    progressMillis: Long,
    viewCount: Long = 0L,
    durationMillis: Long = hours(9) + minutes(26),
  ) = Audiobook(
    id = "1001",
    source = TEST_SOURCE,
    duration = durationMillis,
    progress = progressMillis,
    viewCount = viewCount,
  )

  /**
   * Guards the guard. Every assertion here distinguishes the three states **by resource id**, so if
   * two ids were equal — or all three were 0, which is how an unresolved `R` field reads — the
   * suite would pass while the states were indistinguishable.
   */
  @Test
  fun `the three states are told apart by distinct resource ids`() {
    val ids =
      setOf(
        R.string.details_total_length,
        R.string.details_left,
        R.string.details_finished,
      )

    assertEquals("the three state strings must resolve to distinct ids", 3, ids.size)
  }

  @Test
  fun `an unstarted book shows its total length, not a countdown`() {
    assertEquals(
      length("9h 26m"),
      progress(BookProgressState.Unstarted, hours(9) + minutes(26)),
    )
  }

  @Test
  fun `a started book shows what is left, matching the player`() {
    val duration = hours(9) + minutes(26)

    assertEquals(
      left("6h 12m"),
      progress(BookProgressState.InProgress(duration - (hours(6) + minutes(12))), duration),
    )
  }

  @Test
  fun `a finished book names the state rather than counting down to nothing`() {
    assertEquals(finished(), progress(BookProgressState.Completed, hours(9)))
  }

  /**
   * Tracks not loaded yet. Blank rather than `0m`, so the row does not briefly claim the book has
   * no length while it is still loading.
   */
  @Test
  fun `an unloaded book renders nothing rather than a zero length`() {
    assertEquals("", progress(BookProgressState.Unstarted, 0L))
    assertEquals("", progress(BookProgressState.Unstarted, -1L))
  }

  /**
   * **The bug this had.** "Mark as played" zeroes the position — `markTracksInBookAsWatched` sets
   * every track to `progress = 0`, and `setWatched` then calls `resetBookProgress` — so a finished
   * book and a never-opened one both sit at zero. Branching on position rendered a book the user
   * had just marked played as `9h 26m`, its total length, identical to one never opened, with only
   * the eye icon on the same screen disagreeing.
   *
   * Decision-16 is what settles it: completion is an explicit fact (`viewCount`), never inferred
   * from position. This asserts the seam — a real `Audiobook` in that exact state, through the real
   * `progressState()`, reaching the right words.
   */
  @Test
  fun `a book marked as played reads as finished, not as unstarted`() {
    val markedPlayed = book(progressMillis = 0L, viewCount = 1L)

    assertEquals(finished(), progress(markedPlayed.progressState(), markedPlayed.duration))
  }

  /** And the same book with no `viewCount` is genuinely unstarted, so the two do not collapse. */
  @Test
  fun `a never-opened book at the same zero position reads as unstarted`() {
    val neverOpened = book(progressMillis = 0L)

    assertEquals(length("9h 26m"), progress(neverOpened.progressState(), neverOpened.duration))
  }

  /**
   * How close to the end counts as finished is `BOOK_FINISHED_END_WINDOW`'s decision, not this
   * object's — this pins that the two agree. A second threshold here is what would let the details
   * screen say `1m left` about a book the library already shows as complete, and an earlier draft
   * of this file had exactly that, at 60s against the app's 2 minutes.
   */
  @Test
  fun `a book inside the apps finished window reads as finished here too`() {
    val duration = hours(9)
    val nearlyDone = book(progressMillis = duration - 90_000L, durationMillis = duration)

    assertEquals(finished(), progress(nearlyDone.progressState(), duration))
  }

  /** A resumed book is in progress, however slightly — three seconds in is not "untouched". */
  @Test
  fun `a book resumed seconds in is started, not unstarted`() {
    val duration = hours(9)
    val justStarted = book(progressMillis = 3_000L, durationMillis = duration)

    assertEquals(left("8h 59m"), progress(justStarted.progressState(), duration))
  }
}
