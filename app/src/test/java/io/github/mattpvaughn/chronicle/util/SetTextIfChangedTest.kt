package io.github.mattpvaughn.chronicle.util

import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [setTextIfChanged] must not touch the view when the text is unchanged (cu-117).
 *
 * `TextView.setText` re-lays-out even when handed an equal string, which is why the player's
 * per-second text writes showed up as measure/layout cost. The property that matters is not "the
 * text is correct" — a plain `setText` gets that right too — but "an unchanged value performs no
 * write", so the counter below is the real assertion.
 */
@RunWith(RobolectricTestRunner::class)
class SetTextIfChangedTest {
  /** Counts real writes by observing `setText`, which is what invalidates the view. */
  private class CountingTextView(
    context: android.content.Context,
  ) : TextView(context) {
    var writes = 0

    override fun setText(
      text: CharSequence?,
      type: BufferType?,
    ) {
      writes++
      super.setText(text, type)
    }
  }

  private fun view() = CountingTextView(ApplicationProvider.getApplicationContext())

  @Test
  fun `a new value is written`() {
    val v = view()
    val before = v.writes

    v.setTextIfChanged("1:23:45")

    assertEquals("1:23:45", v.text.toString())
    assertEquals(before + 1, v.writes)
  }

  /** The whole point: a repeated value costs nothing. */
  @Test
  fun `an unchanged value is not written again`() {
    val v = view()
    v.setTextIfChanged("42%")
    val afterFirst = v.writes

    repeat(10) { v.setTextIfChanged("42%") }

    assertEquals(afterFirst, v.writes)
    assertEquals("42%", v.text.toString())
  }

  @Test
  fun `a changed value after repeats is written`() {
    val v = view()
    v.setTextIfChanged("42%")
    repeat(5) { v.setTextIfChanged("42%") }
    val before = v.writes

    v.setTextIfChanged("43%")

    assertEquals(before + 1, v.writes)
    assertEquals("43%", v.text.toString())
  }

  /** Empty is a legitimate value, not a "no value" — clearing must still work. */
  @Test
  fun `clearing to empty is written once`() {
    val v = view()
    v.setTextIfChanged("something")
    val before = v.writes

    v.setTextIfChanged("")
    v.setTextIfChanged("")

    assertEquals(before + 1, v.writes)
    assertEquals("", v.text.toString())
  }
}
