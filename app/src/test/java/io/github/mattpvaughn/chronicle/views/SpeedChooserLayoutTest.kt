package io.github.mattpvaughn.chronicle.views

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ApplicationProvider
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.databinding.ModalBottomSheetSpeedChooserBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The speed popover's content survives a window too short to hold it (cu-142).
 *
 * **What this does and does not cover.** The reported bug — the sheet collapsing to its title bar
 * in landscape — turned out *not* to be a layout measurement failure at all: the layout measures
 * 356px in both orientations, with or without the scroll view. It was Material's
 * `BottomSheetDialog` opening at its landscape **peek height**, which is dialog behaviour
 * Robolectric does not reproduce, and `ModalBottomSheetSpeedChooser.onStart` is what fixes it.
 * That half is device-verified and cannot be asserted here; a test that passed either way was
 * written first and deleted for exactly that reason.
 *
 * What *is* checkable headless is the second half: an expanded sheet in a short window must scroll
 * rather than clip. Measured on the tablet at 480px tall, the un-scrolled sheet cut the
 * skip-silence switch to 60px of its 72 — a control half off the screen with no way to reach it.
 */
@RunWith(RobolectricTestRunner::class)
class SpeedChooserLayoutTest {
  private fun inflate(): ModalBottomSheetSpeedChooserBinding {
    val context: Context =
      ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.AppTheme)
    return ModalBottomSheetSpeedChooserBinding.inflate(LayoutInflater.from(context))
  }

  /**
   * The controls sit inside a scrolling container.
   *
   * Structural rather than behavioural, because the clipping it prevents only happens inside a real
   * dialog window. Without this the sheet cannot be made to scroll at all, whatever height the
   * dialog gives it.
   */
  @Test
  fun `the sheet's controls live inside a scroll view`() {
    val binding = inflate()

    val scroller =
      generateSequence(binding.speedSlider.parent) { it.parent }
        .filterIsInstance<NestedScrollView>()
        .firstOrNull()

    assertNotNull(
      "the controls must be inside a NestedScrollView, or a window shorter than the content " +
        "clips the last control instead of letting the user reach it",
      scroller,
    )
  }

  /**
   * The scroll view fills the height it is given.
   *
   * Without `fillViewport` it sizes to its content, which reintroduces exactly the collapse this
   * is meant to prevent when the dialog hands it a height to fill.
   */
  @Test
  fun `the scroll view fills its viewport`() {
    val binding = inflate()

    val scroller =
      generateSequence(binding.speedSlider.parent) { it.parent }
        .filterIsInstance<NestedScrollView>()
        .first()

    assertTrue("the scroll view must set fillViewport", scroller.isFillViewport)
  }

  /**
   * Content taller than the window becomes scrollable rather than being cut off.
   *
   * Measures the real thing: lay the sheet out in a window shorter than its content and assert the
   * scroll view reports somewhere to scroll to. `computeVerticalScrollRange` exceeding the height
   * is the definition of "there is more below" — which is what the last control being reachable
   * depends on.
   */
  @Test
  fun `content taller than the window can be scrolled to`() {
    val binding = inflate()
    val root = binding.root
    val scroller =
      generateSequence(binding.speedSlider.parent) { it.parent }
        .filterIsInstance<NestedScrollView>()
        .first()

    val width = 960
    // Deliberately shorter than the ~356px the content measures, matching the 480px-tall landscape
    // window where the tablet clipped the skip-silence switch.
    val shortHeight = 200
    root.measure(
      View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(shortHeight, View.MeasureSpec.EXACTLY),
    )
    root.layout(0, 0, width, shortHeight)

    assertTrue(
      "the content (${scroller.computeVerticalScrollRange()}px) should exceed the viewport " +
        "(${scroller.height}px), so there is something to scroll to",
      scroller.computeVerticalScrollRange() > scroller.height,
    )
  }

  /** Every control keeps a real size even in that short window — clipped is not the same as absent. */
  @Test
  fun `no control measures to zero in a short window`() {
    val binding = inflate()
    val root = binding.root
    root.measure(
      View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
    )
    root.layout(0, 0, 960, 200)

    mapOf(
      "speed slider" to binding.speedSlider,
      "preset chips" to binding.speedPresets,
      "per-book switch" to binding.perBookSpeedSwitch,
      "skip-silence switch" to binding.skipSilenceSwitch,
    ).forEach { (name, view) ->
      assertTrue("$name measured ${view.measuredWidth}x${view.measuredHeight}", view.measuredHeight > 0)
    }
  }

  /** The handle is a fixed height, so a sheet showing only it is showing nothing. */
  @Test
  fun `the content is taller than the title bar alone`() {
    val binding = inflate()
    val context = binding.root.context
    binding.root.measure(
      View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
    )

    val handle = context.resources.getDimensionPixelSize(R.dimen.bottom_sheet_handle_height)
    assertTrue(
      "the sheet measured ${binding.root.measuredHeight}px against a ${handle}px title bar",
      binding.root.measuredHeight > handle,
    )
    assertEquals(handle, binding.bottomSheetHandle.measuredHeight)
  }
}
