package io.github.mattpvaughn.chronicle.features.library

import android.view.View
import android.widget.ProgressBar
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The three states a book can show in the library list.
 *
 * There used to be two: not-played, and in-progress. A finished book rendered as *in progress* at
 * whatever position it held — and once "mark as read" reset that position to 0, it rendered as
 * **not played**, indistinguishable from a book never opened. That is the owner's *"in the list the
 * book is not always marked with the expected state"* (cu-86).
 */
@RunWith(RobolectricTestRunner::class)
class ProgressIndicatorTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun bind(book: Audiobook): Pair<View, ProgressBar> {
    val dogEar = View(context)
    val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    bindProgressIndicators(dogEar, bar, book)
    return dogEar to bar
  }

  private fun book(
    progress: Long = 0L,
    duration: Long = 3_600_000L,
    viewCount: Long = 0L,
  ) = Audiobook(
    id = "1001",
    source = 1L,
    title = "Dune",
    progress = progress,
    duration = duration,
    viewCount = viewCount,
  )

  private fun View.shown(): Boolean = visibility == View.VISIBLE

  @Test
  fun `an unstarted book shows the dog-ear and no bar`() {
    val (dogEar, bar) = bind(book())

    assertTrue("an untouched book is marked not-played", dogEar.shown())
    assertFalse(bar.shown())
  }

  @Test
  fun `a part-finished book shows the bar at its position and no dog-ear`() {
    val (dogEar, bar) = bind(book(progress = 1_800_000L))

    assertFalse(dogEar.shown())
    assertTrue(bar.shown())
    assertEquals(1_800_000, bar.progress)
  }

  /** The regression: marked as read *and* reset to 0 must not look like never-opened. */
  @Test
  fun `a book marked as read at zero progress shows finished, not unstarted`() {
    val (dogEar, bar) = bind(book(progress = 0L, viewCount = 1L))

    assertFalse("a book marked as read must not show as never played", dogEar.shown())
    assertTrue(bar.shown())
    assertEquals("a finished book's bar is full", bar.max, bar.progress)
  }

  @Test
  fun `a book listened to the end shows a full bar`() {
    val (_, bar) = bind(book(progress = 3_600_000L))

    assertEquals(bar.max, bar.progress)
  }

  /**
   * A book whose duration has not loaded yet must not render as finished. `ProgressBar.max = 0`
   * silently makes any progress value render as complete.
   */
  @Test
  fun `a book with no duration does not render as finished`() {
    val (dogEar, bar) = bind(book(progress = 0L, duration = 0L))

    assertTrue("no duration and no progress is still unstarted", dogEar.shown())
    assertTrue("max must never be zero", bar.max >= 1)
  }
}
