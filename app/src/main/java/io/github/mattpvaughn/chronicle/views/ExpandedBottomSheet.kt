package io.github.mattpvaughn.chronicle.views

import android.view.View
import androidx.fragment.app.DialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Opens a modal bottom sheet fully expanded rather than at its peek height (cu-142).
 *
 * A Material `BottomSheetDialog` in **landscape** opens collapsed at a peek height and expects the
 * user to drag it up. For a sheet whose content is `wrap_content` that peek settled at 96px on the
 * tablet — shorter than the sheet's own 108px title bar — so the speed slider, the four presets and
 * both switches were entirely unreachable, and nothing on screen suggested there was anything to
 * drag. Speed was unadjustable in landscape, the primary orientation for a tablet on a stand.
 *
 * Shared rather than written per sheet because all three of this app's modal sheets have the same
 * shape and therefore the same latent bug; the speed chooser is simply the one where it was noticed.
 *
 * **This is the half that fixes the collapse**, and it cannot be asserted in a unit test —
 * Robolectric does not reproduce the dialog's peek behaviour, and the layout measures identically
 * with or without it. The layout's `NestedScrollView` is the complementary half: it makes "fully
 * expanded" safe on a window too short for the content, which `SpeedChooserLayoutTest` does cover.
 */
fun DialogFragment.expandBottomSheetOnStart() {
  val bottomSheetDialog = dialog as? BottomSheetDialog ?: return
  val sheet =
    bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
      ?: return
  BottomSheetBehavior.from(sheet).apply {
    state = BottomSheetBehavior.STATE_EXPANDED
    // Without this a drag down settles back into the same invisible collapsed state instead of
    // dismissing — which reads as the sheet breaking rather than closing.
    skipCollapsed = true
  }
}
