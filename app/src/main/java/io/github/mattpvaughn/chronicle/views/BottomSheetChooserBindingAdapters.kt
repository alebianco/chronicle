package io.github.mattpvaughn.chronicle.views

import android.view.View
import android.widget.TextView
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.BottomChooserState
import io.github.mattpvaughn.chronicle.views.BottomSheetChooser.FormattableString

fun setBottomChooserState(
  bottomSheetChooser: BottomSheetChooser,
  state: BottomChooserState,
) {
  bottomSheetChooser.setTitle(state.title)
  bottomSheetChooser.setOptionsSelectedListener(state.listener)
  if (state.shouldShow) {
    // Don't run showing animation if it's already showing
    if (bottomSheetChooser.findViewById<View>(R.id.tap_to_close).visibility != View.VISIBLE) {
      bottomSheetChooser.show()
    }
  } else {
    bottomSheetChooser.hide(false)
  }
  bottomSheetChooser.setOptions(state.options)
}

fun setFormattableText(
  textView: TextView,
  formattableString: FormattableString,
) {
  textView.text = formattableString.format(textView.context.resources)
}
