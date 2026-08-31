package io.github.mattpvaughn.chronicle.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Pads [this] by the top system-bar inset, so a toolbar sits below the status bar
 * rather than under it.
 *
 * `targetSdk 36` enforces edge-to-edge and stops Android insetting app content,
 * so any view that reaches the top of the window has to do this itself (cu-63).
 *
 * The original padding is captured on first call and used as the base, so
 * repeated calls — which happen whenever insets change, e.g. on rotation — add
 * the inset once rather than accumulating it.
 */
fun View.applyTopSystemBarInset() {
  val initialTopPadding = paddingTop
  ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
    val top = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top
    view.updatePadding(top = initialTopPadding + top)
    windowInsets
  }
  // A view already attached will not receive a fresh dispatch on its own.
  ViewCompat.requestApplyInsets(this)
}
