package io.github.mattpvaughn.chronicle.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [ChronicleColors] against `res/values/colors.xml`.
 *
 * The Compose colours are Kotlin literals rather than `colorResource` lookups, because a `@Preview`
 * and a Compose UI test render with no Android theme attached — resolving a theme attribute there
 * either fails or silently yields a stock Material colour. That duplication is deliberate, but
 * duplication without a guard is drift waiting to happen: someone retunes `colorAccent` in XML, and
 * every Compose screen quietly keeps the old cyan while every XML screen changes.
 *
 * This is the guard. It reads the real resource and compares.
 */
@RunWith(RobolectricTestRunner::class)
class ChronicleThemeTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun resource(id: Int): Int = context.getColor(id)

  private fun assertMatches(
    name: String,
    resourceId: Int,
    composeColor: Color,
  ) {
    assertEquals(
      "$name in ChronicleColors has drifted from res/values/colors.xml",
      resource(resourceId),
      composeColor.toArgb(),
    )
  }

  @Test
  fun `the compose palette matches the xml palette`() {
    assertMatches("colorPrimary", R.color.colorPrimary, ChronicleColors.Primary)
    assertMatches("colorPrimaryDark", R.color.colorPrimaryDark, ChronicleColors.PrimaryDark)
    assertMatches("colorAccent", R.color.colorAccent, ChronicleColors.Accent)
    assertMatches("textPrimary", R.color.textPrimary, ChronicleColors.TextPrimary)
    assertMatches("textSecondary", R.color.textSecondary, ChronicleColors.TextSecondary)
    assertMatches("textError", R.color.textError, ChronicleColors.TextError)
  }
}
