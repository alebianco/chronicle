package io.github.mattpvaughn.chronicle.views

import android.app.Dialog
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import androidx.fragment.app.DialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.mattpvaughn.chronicle.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * A modal sheet opens expanded rather than at its peek height (cu-142).
 *
 * The behaviour this pins made the speed popover's controls unreachable in landscape: Material
 * opens a `BottomSheetDialog` collapsed at a peek height, which for this sheet settled at 96px —
 * shorter than its own 108px title bar — with nothing on screen to suggest there was anything to
 * drag.
 *
 * Robolectric does not reproduce the *collapse* (the dialog never lays out for real), so this
 * cannot assert the pixel outcome — that half is device-verified. What it does assert is that the
 * helper reaches the behaviour and sets it, which is the part a later refactor could silently drop:
 * `findViewById(design_bottom_sheet)` returning null would make the whole thing a no-op that looks
 * fine.
 */
@RunWith(RobolectricTestRunner::class)
class ExpandedBottomSheetTest {
  /** A minimal sheet that does nothing but call the helper, so the helper is what is measured. */
  class TestSheet : DialogFragment() {
    var behaviorAfterStart: BottomSheetBehavior<View>? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
      val themed = ContextThemeWrapper(requireContext(), R.style.AppTheme)
      return BottomSheetDialog(themed).apply {
        setContentView(View(themed).apply { minimumHeight = 800 })
      }
    }

    override fun onStart() {
      super.onStart()
      expandBottomSheetOnStart()
      val sheet =
        (dialog as? BottomSheetDialog)
          ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
      behaviorAfterStart = sheet?.let { BottomSheetBehavior.from(it) }
    }
  }

  private fun showSheet(): TestSheet {
    val controller =
      ActivityController.of(androidx.fragment.app.FragmentActivity()).setup()
    val activity = controller.get()
    activity.setTheme(R.style.AppTheme)
    val sheet = TestSheet()
    sheet.show(activity.supportFragmentManager, "test")
    activity.supportFragmentManager.executePendingTransactions()
    return sheet
  }

  @Test
  @Config(qualifiers = "w1920dp-h1128dp-land")
  fun `the sheet is expanded, not collapsed, in landscape`() {
    val behavior = showSheet().behaviorAfterStart

    assertEquals(
      "an unexpanded sheet opens at its peek height, which hid every control",
      BottomSheetBehavior.STATE_EXPANDED,
      behavior?.state,
    )
  }

  @Test
  @Config(qualifiers = "w800dp-h1204dp-port")
  fun `the sheet is expanded in portrait too`() {
    val behavior = showSheet().behaviorAfterStart

    assertEquals(BottomSheetBehavior.STATE_EXPANDED, behavior?.state)
  }

  /**
   * A drag down dismisses rather than settling back into the collapsed state.
   *
   * Without `skipCollapsed` the sheet returns to the same invisible peek the fix exists to avoid,
   * which reads as the sheet breaking rather than closing.
   */
  @Test
  @Config(qualifiers = "w1920dp-h1128dp-land")
  fun `the sheet skips the collapsed state`() {
    val behavior = showSheet().behaviorAfterStart

    assertTrue("a drag down must dismiss, not re-collapse", behavior?.skipCollapsed == true)
  }

  /** The helper is a no-op rather than a crash for a fragment that is not a bottom sheet. */
  @Test
  fun `a non-bottom-sheet dialog is left alone`() {
    class PlainSheet : DialogFragment() {
      var survived = false

      override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = Dialog(ContextThemeWrapper(requireContext(), R.style.AppTheme))

      override fun onStart() {
        super.onStart()
        expandBottomSheetOnStart()
        survived = true
      }
    }

    val controller = ActivityController.of(androidx.fragment.app.FragmentActivity()).setup()
    val activity = controller.get()
    activity.setTheme(R.style.AppTheme)
    val sheet = PlainSheet()
    sheet.show(activity.supportFragmentManager, "plain")
    activity.supportFragmentManager.executePendingTransactions()

    assertTrue("the helper must not throw for a dialog that is not a bottom sheet", sheet.survived)
  }
}
