package io.github.mattpvaughn.chronicle.views

import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Asserts the text palette meets the WCAG-AA contrast floor (cu-4).
 *
 * Contrast is easy to regress by eye — a colour that looks fine to someone with
 * good vision on a bright screen can be unreadable otherwise — so the ratios are
 * pinned here rather than left to review. Values are read from colors.xml, so
 * editing a colour re-checks it automatically.
 */
@RunWith(RobolectricTestRunner::class)
class ColorContrastTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun color(id: Int): Int = context.getColor(id)

  /** Relative luminance per WCAG 2.1. */
  private fun luminance(color: Int): Double {
    fun channel(c: Int): Double {
      val v = c / 255.0
      return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    val r = channel((color shr 16) and 0xFF)
    val g = channel((color shr 8) and 0xFF)
    val b = channel(color and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
  }

  /** Composites a translucent foreground over an opaque background. */
  private fun flatten(
    foreground: Int,
    background: Int,
  ): Int {
    val alpha = ((foreground shr 24) and 0xFF) / 255.0

    fun mix(shift: Int): Int {
      val f = (foreground shr shift) and 0xFF
      val b = (background shr shift) and 0xFF
      return (f * alpha + b * (1 - alpha)).toInt()
    }
    return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
  }

  private fun contrast(
    foreground: Int,
    background: Int,
  ): Double {
    val f = luminance(flatten(foreground, background))
    val b = luminance(background)
    return (max(f, b) + 0.05) / (min(f, b) + 0.05)
  }

  private fun assertMeetsAa(
    foregroundName: String,
    foreground: Int,
    backgroundName: String,
    background: Int,
  ) {
    val ratio = contrast(foreground, background)
    assertTrue(
      "$foregroundName on $backgroundName is %.2f:1, below the WCAG-AA 4.5:1 floor".format(ratio),
      ratio >= AA_NORMAL_TEXT,
    )
  }

  @Test
  fun `text colours meet WCAG-AA against both surface colours`() {
    val surfaces =
      listOf(
        "colorPrimary" to color(R.color.colorPrimary),
        "colorPrimaryDark" to color(R.color.colorPrimaryDark),
      )
    val textColors =
      listOf(
        "textPrimary" to color(R.color.textPrimary),
        "textSecondary" to color(R.color.textSecondary),
        "textActive" to color(R.color.textActive),
        "textActiveSecondary" to color(R.color.textActiveSecondary),
        // Regressed to 3.81:1 on colorPrimary before cu-4 — error text is
        // exactly where legibility matters most.
        "textError" to color(R.color.textError),
      )

    for ((surfaceName, surface) in surfaces) {
      for ((textName, text) in textColors) {
        assertMeetsAa(textName, text, surfaceName, surface)
      }
    }
  }

  companion object {
    private const val AA_NORMAL_TEXT = 4.5
  }
}
