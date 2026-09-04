package io.github.mattpvaughn.chronicle.features.settings

import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A sample row is a control, and says so (cu-151).
 *
 * Tapping loads the title into the tester rather than making the user retype it, so the row has to
 * announce what a tap *does* — the bare title alone does not tell a screen-reader user that the
 * row is actionable at all (cu-47).
 */
@RunWith(RobolectricTestRunner::class)
class SampleTitleAdapterTest {
  /**
   * Themed, not the bare application context.
   *
   * The row layouts use `?attr/selectableItemBackground`, which Robolectric's default theme does
   * not define — inflation throws `InflateException` rather than falling back. `AppTheme` is what
   * the manifest applies, so this is also the theme the rows actually render under.
   */
  private val context =
    android.view.ContextThemeWrapper(
      ApplicationProvider.getApplicationContext(),
      io.github.mattpvaughn.chronicle.R.style.AppTheme,
    )
  private val parent = FrameLayout(context)

  private fun boundRow(
    title: String,
    onChosen: (String) -> Unit = {},
  ): android.view.View {
    val adapter = SampleTitleAdapter(onChosen)
    adapter.submitList(listOf(title))
    val holder = adapter.onCreateViewHolder(parent, 0)
    adapter.onBindViewHolder(holder, 0)
    return holder.itemView
  }

  @Test
  fun `tapping a sample reports the title it carries`() {
    val chosen = mutableListOf<String>()

    boundRow("Warhammer 40,000 - Horus Rising") { chosen.add(it) }.performClick()

    assertEquals(listOf("Warhammer 40,000 - Horus Rising"), chosen)
  }

  @Test
  fun `the row announces that tapping it runs the test`() {
    val title = "Warhammer 40,000 - Horus Rising"

    val row = boundRow(title)

    val description =
      row.findViewById<android.widget.TextView>(R.id.sample_title).contentDescription.toString()
    assertEquals(context.getString(R.string.series_rules_sample_description, title), description)
    assertTrue("the title itself must still be spoken", description.contains(title))
  }
}
