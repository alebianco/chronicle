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

/**
 * Pads [this] by the top system-bar inset **and** grows its `minHeight` to match, so a collapsing
 * app bar still owns the status-bar strip once it has scrolled away (DRAFT-105).
 *
 * [applyTopSystemBarInset] alone is not enough for a `CollapsingToolbarLayout`. Padding moves the
 * bar's *content* below the status bar but does not change how far the bar may collapse, so with
 * `exitUntilCollapsed` it shrinks to `minHeight` — measured *excluding* the padding. On a device
 * with a 48px status bar the bar collapsed to exactly 48px, the toolbar was squeezed into the inset
 * region, and the scrolling list slid up to y=48: list rows appeared in the status-bar strip above
 * the toolbar before leaving the screen, which is what the owner reported.
 *
 * Growing `minHeight` by the same inset means the collapsed bar is `actionBarSize + inset` tall —
 * the toolbar keeps its full height and the strip above it stays covered.
 *
 * Apply this to the view carrying `minHeight` (the `CollapsingToolbarLayout`), not to the
 * `AppBarLayout` around it.
 */
fun View.applyTopSystemBarInsetWithMinHeight() {
  val initialTopPadding = paddingTop
  val initialMinHeight = minimumHeight
  ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
    val top = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top
    view.updatePadding(top = initialTopPadding + top)
    view.minimumHeight = initialMinHeight + top
    windowInsets
  }
  ViewCompat.requestApplyInsets(this)
}
