package io.github.mattpvaughn.chronicle.features.bookdetails

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.databinding.FragmentAudiobookDetailsBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The metadata block stacks without overlapping, however many rows are showing (cu-145).
 *
 * Adding narrator and series to the packed chain squeezed it: bounded top *and* bottom by the play
 * button, four rows were laid out in the space sized for two and "Narrated by Michael Kramer"
 * overlapped "Mistborn, Book 10" by 17px on the tablet. Visible, readable, and wrong.
 *
 * Measured rather than eyeballed because the overlap is small enough to miss in a screenshot and
 * invisible to a `uiautomator` dump, which reports bounds without noticing they intersect.
 */
@RunWith(RobolectricTestRunner::class)
class BookDetailsMetadataLayoutTest {
  private fun laidOut(
    showNarrator: Boolean,
    showSeries: Boolean,
  ): FragmentAudiobookDetailsBinding {
    val context: Context =
      ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.AppTheme)
    val binding = FragmentAudiobookDetailsBinding.inflate(LayoutInflater.from(context))
    binding.bookTitle.text = "Mistborn Book 10"
    binding.author.text = "Brandon Sanderson"
    binding.narrator.text = "Narrated by Michael Kramer"
    binding.narrator.visibility = if (showNarrator) View.VISIBLE else View.GONE
    binding.series.text = "Mistborn, Book 10"
    binding.series.visibility = if (showSeries) View.VISIBLE else View.GONE
    val display = context.resources.displayMetrics
    binding.root.measure(
      View.MeasureSpec.makeMeasureSpec(display.widthPixels, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(display.heightPixels, View.MeasureSpec.AT_MOST),
    )
    binding.root.layout(0, 0, binding.root.measuredWidth, binding.root.measuredHeight)
    return binding
  }

  private fun assertStacked(rows: List<Pair<String, View>>) {
    val shown = rows.filter { it.second.visibility == View.VISIBLE }
    shown.zipWithNext { (aName, a), (bName, b) ->
      assertTrue(
        "$aName [${a.top}..${a.bottom}] overlaps $bName [${b.top}..${b.bottom}]",
        a.bottom <= b.top,
      )
      assertTrue("$aName has no height", a.height > 0)
      assertTrue("$bName has no height", b.height > 0)
    }
  }

  @Test
  fun `all four rows stack without overlapping`() {
    val b = laidOut(showNarrator = true, showSeries = true)

    assertStacked(
      listOf("title" to b.bookTitle, "author" to b.author, "narrator" to b.narrator, "series" to b.series),
    )
  }

  /** The common state today: a book opened but with no series tag. */
  @Test
  fun `narrator alone stacks under the author`() {
    val b = laidOut(showNarrator = true, showSeries = false)

    assertStacked(listOf("title" to b.bookTitle, "author" to b.author, "narrator" to b.narrator))
    assertEquals(View.GONE, b.series.visibility)
  }

  @Test
  fun `series alone stacks under the author`() {
    val b = laidOut(showNarrator = false, showSeries = true)

    assertStacked(listOf("title" to b.bookTitle, "author" to b.author, "series" to b.series))
  }

  /**
   * The state most books are in until cu-143 seeds the index.
   *
   * With both rows GONE the block must lay out exactly as it did before this task — a hidden row
   * that still takes space would push the summary down on every untagged book.
   */
  @Test
  fun `with neither row the title and author still stack`() {
    val b = laidOut(showNarrator = false, showSeries = false)

    assertStacked(listOf("title" to b.bookTitle, "author" to b.author))
    assertEquals(0, b.narrator.height)
    assertEquals(0, b.series.height)
  }
}
